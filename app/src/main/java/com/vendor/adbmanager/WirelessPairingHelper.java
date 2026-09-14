package com.vendor.adbmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * 无线调试配对辅助类。
 *
 * 车机（Android 11，RQ3A.211001.001）实测的配对广播流程：
 * 1) 调用 enablePairingByPairingCode() 后，系统先随机生成 6 位配对码，通过
 *    ACTION_WIRELESS_PAIRING_RESULT + status=3 + pairing_code 广播；
 * 2) adbd 的配对服务启动后，再通过 ACTION_WIRELESS_PAIRING_RESULT + status=4 + adb_port
 *    广播真正的“配对端口”（与 TLS 连接端口不同）；
 * 3) ACTION_WIRELESS_STATUS + status=4/5 广播的是 TLS 连接端口的状态变化，与配对无关。
 *
 * 之前只监听 ACTION_WIRELESS_STATUS，收到的 status=4 不属于配对码，导致一直超时“生成失败”。
 */
public class WirelessPairingHelper {

    private static final String TAG = "AdbManager.Pairing";

    /** 配对结果广播：status=3 携带 pairing_code，status=4 携带配对端口 adb_port */
    public static final String ACTION_WIRELESS_PAIRING_RESULT =
            "com.android.server.adb.WIRELESS_DEBUG_PAIRING_RESULT";
    /** 无线调试连接状态广播：status=4 已连接 / status=5 已断开，adb_port 为连接端口 */
    public static final String ACTION_WIRELESS_STATUS =
            "com.android.server.adb.WIRELESS_DEBUG_STATUS";

    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PAIRING_CODE = "pairing_code";
    public static final String EXTRA_ADB_PORT = "adb_port";

    /** AdbManager.WIRELESS_STATUS_PAIRING_CODE */
    public static final int STATUS_PAIRING_CODE = 3;
    /** AdbManager.WIRELESS_STATUS_CONNECTED */
    public static final int STATUS_CONNECTED = 4;

    /** 配对服务在 mDNS 上注册的类型，用于兜底获取配对端口 */
    private static final String NSD_PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp";
    private static final long PAIRING_TIMEOUT_MS = 15000;
    private static final long PORT_FALLBACK_DELAY_MS = 1200;

    public interface PairingCallback {
        void onPairingCode(String code, int port);
        void onError(String msg);
    }

    private final Context context;
    private PairingCallback callback;
    private BroadcastReceiver receiver;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private Runnable portFallbackRunnable;

    private String pairingCode;
    private int pairingPort = -1;
    private boolean codeNotified;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    public WirelessPairingHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    /** 启动配对：开启无线调试 -> 请求生成配对码 -> 等待系统广播（配对码 / 配对端口） */
    public void startPairing(PairingCallback cb) {
        this.callback = cb;
        this.pairingCode = null;
        this.pairingPort = -1;
        this.codeNotified = false;

        try {
            android.provider.Settings.Global.putInt(
                    context.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "已开启系统无线调试(adb_wifi_enabled=1)");
        } catch (Exception e) {
            Log.w(TAG, "开启无线调试失败(可能需要系统签名)", e);
        }

        registerReceiver();

        timeoutRunnable = () -> {
            if (pairingCode != null) {
                Log.w(TAG, "超时未等到配对端口广播，使用当前已知端口=" + pairingPort);
                notifyCode(pairingCode, pairingPort);
            } else {
                onError("系统未在超时内返回配对码（需 Android 11+ 且已开启开发者选项）");
            }
        };
        handler.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS);

        try {
            Object svc = getAdbManagerBinder();
            if (svc == null) {
                onError("无法获取 adb 系统服务（需要 system 签名）");
                return;
            }
            Method m = svc.getClass().getMethod("enablePairingByPairingCode");
            m.invoke(svc);
            Log.i(TAG, "已请求系统生成无线调试配对码，等待系统广播...");
        } catch (Exception e) {
            Log.e(TAG, "enablePairingByPairingCode 失败", e);
            onError("调用系统配对服务失败: " + e.getMessage());
        }
    }

    public void stopPairing() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(portFallbackRunnable);
        stopPortDiscovery();
        try {
            Object svc = getAdbManagerBinder();
            if (svc != null) {
                Method m = svc.getClass().getMethod("disablePairing");
                m.invoke(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "disablePairing 失败", e);
        }
        unregister();
    }

    /**
     * 处理系统广播。配对结果广播里才带配对端口；连接状态广播里的端口是已建立的连接端口，
     * 两者容易混淆，因此只取 ACTION_WIRELESS_PAIRING_RESULT 的端口。
     */
    private void handleBroadcast(Intent intent) {
        final String action = intent.getAction();
        final int status = intent.getIntExtra(EXTRA_STATUS, -1);
        final String code = intent.getStringExtra(EXTRA_PAIRING_CODE);
        final int port = intent.getIntExtra(EXTRA_ADB_PORT, -1);
        Log.i(TAG, "收到广播: action=" + action + " status=" + status
                + " pairing_code=" + code + " adb_port=" + port);

        boolean isPairingResult = ACTION_WIRELESS_PAIRING_RESULT.equals(action);

        if (code != null && !code.isEmpty()) {
            pairingCode = code;
        }
        if (isPairingResult && port > 0 && port <= 65535) {
            pairingPort = port;
        }

        if (pairingCode == null) {
            return;
        }
        if (pairingPort > 0) {
            notifyCode(pairingCode, pairingPort);
        } else if (!codeNotified) {
            // 配对码先到、端口后到：先把码回调出去，再延迟用 mDNS 兜底查询端口
            notifyCode(pairingCode, -1);
            portFallbackRunnable = this::startPortDiscovery;
            handler.postDelayed(portFallbackRunnable, PORT_FALLBACK_DELAY_MS);
        }
    }

    /** 回调配对信息；port<=0 表示端口尚未拿到，拿到后会再次回调 */
    private void notifyCode(String code, int port) {
        if (callback == null) return;
        codeNotified = true;
        if (port > 0) {
            handler.removeCallbacks(timeoutRunnable);
            handler.removeCallbacks(portFallbackRunnable);
            stopPortDiscovery();
        }
        Log.i(TAG, "回调配对信息: code=" + code + " port=" + port);
        callback.onPairingCode(code, port);
    }

    /** 兜底：系统未广播配对端口时，从 mDNS(_adb-tls-pairing._tcp) 发现本机配对端口 */
    private void startPortDiscovery() {
        if (pairingPort > 0 || discoveryListener != null) return;
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            final String serial = readSerial();
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String type, int err) {
                    Log.w(TAG, "mDNS 启动失败: " + type + " err=" + err);
                }

                @Override
                public void onStopDiscoveryFailed(String type, int err) {
                }

                @Override
                public void onDiscoveryStarted(String type) {
                    Log.i(TAG, "mDNS 开始搜索配对服务: " + type);
                }

                @Override
                public void onDiscoveryStopped(String type) {
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    String name = info.getServiceName();
                    int port = info.getPort();
                    Log.i(TAG, "mDNS 发现服务: " + name + " port=" + port);
                    if (serial != null && !serial.isEmpty() && name != null
                            && !name.contains(serial)) {
                        Log.i(TAG, "非本机配对服务，忽略: " + name);
                        return;
                    }
                    if (port <= 0) return;
                    pairingPort = port;
                    handler.post(() -> {
                        if (pairingCode != null) {
                            notifyCode(pairingCode, pairingPort);
                        }
                    });
                }
            };
            nsdManager.discoverServices(NSD_PAIRING_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.w(TAG, "mDNS 兜底获取配对端口失败", e);
        }
    }

    private void stopPortDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignore) {
            }
        }
        discoveryListener = null;
    }

    /** 读取本机序列号，用于过滤 mDNS 服务（服务名形如 adb-<serial>-xxxx） */
    private String readSerial() {
        try {
            java.lang.Process p = new ProcessBuilder("getprop", "ro.serialno")
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String value = br.readLine();
            p.waitFor();
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            Log.w(TAG, "读取序列号失败", e);
            return "";
        }
    }

    private Object getAdbManagerBinder() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "adb");
        if (binder == null) return null;
        Class<?> stub = Class.forName("android.debug.IAdbManager$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    private void registerReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handleBroadcast(intent);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_WIRELESS_PAIRING_RESULT);
        f.addAction(ACTION_WIRELESS_STATUS);
        try {
            context.registerReceiver(receiver, f, "android.permission.MANAGE_DEBUGGING", null);
        } catch (Exception e) {
            // 带权限注册失败时降级为普通注册
            Log.w(TAG, "带权限注册失败，降级为普通注册", e);
            context.registerReceiver(receiver, f);
        }
    }

    private void unregister() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(portFallbackRunnable);
        stopPortDiscovery();
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignore) {}
            receiver = null;
        }
    }

    private void onError(String msg) {
        Log.e(TAG, msg);
        unregister();
        if (callback != null) callback.onError(msg);
    }
}
