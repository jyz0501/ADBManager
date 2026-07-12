package com.vendor.adbmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 真正的无线调试配对：调用系统隐藏的 IAdbManager 服务启动配对，
 * 再通过系统广播获取系统生成的 6 位配对码与配对端口。
 *
 * 说明（基于 AOSP android-11）：
 *  - enablePairingByPairingCode() 返回 void，配对码由系统在生成后通过
 *    广播 "com.android.server.adb.WIRELESS_DEBUG_STATUS" 发出，
 *    当 extra "status" == 3 (PAIRING_CODE) 时携带 "pairing_code" 与 "adb_port"。
 *  - framework 的 android.debug.AdbManager 在 API 30 未把这些方法暴露为 public，
 *    因此直接反射底层 IAdbManager binder。
 *  - 收听该广播需要 android.permission.MANAGE_DEBUGGING（系统签名应用已具备）。
 */
public class WirelessPairingHelper {
    private static final String TAG = "AdbManager.Pairing";

    public static final String ACTION_WIRELESS_STATUS = "com.android.server.adb.WIRELESS_DEBUG_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PAIRING_CODE = "pairing_code";
    public static final String EXTRA_ADB_PORT = "adb_port";
    public static final int STATUS_PAIRING_CODE = 3;
    private static final long PAIRING_TIMEOUT_MS = 8000;

    public interface PairingCallback {
        void onPairingCode(String code, int port);
        void onError(String msg);
    }

    private final Context context;
    private PairingCallback callback;
    private BroadcastReceiver receiver;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    public WirelessPairingHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void startPairing(PairingCallback cb) {
        this.callback = cb;
        // 系统要求先开启"无线调试"总开关，配对流程才会真正生成配对码。
        // 本应用持有 WRITE_SECURE_SETTINGS，可直接写入该安全设置。
        try {
            android.provider.Settings.Global.putInt(
                    context.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(TAG, "已开启系统无线调试(adp_wifi_enabled=1)");
        } catch (Exception e) {
            Log.w(TAG, "开启无线调试失败(可能需要开发者选项)", e);
        }
        registerReceiver();

        timeoutRunnable = () -> {
            if (receiver != null) {
                onError("系统未在超时内返回配对码（需 Android 11+ 且已开启开发者选项/无线调试）");
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS);

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
        timeoutHandler.removeCallbacks(timeoutRunnable);
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
                if (!ACTION_WIRELESS_STATUS.equals(intent.getAction())) return;
                int status = intent.getIntExtra(EXTRA_STATUS, -1);
                if (status == STATUS_PAIRING_CODE) {
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    String code = intent.getStringExtra(EXTRA_PAIRING_CODE);
                    int port = intent.getIntExtra(EXTRA_ADB_PORT, -1);
                    Log.i(TAG, "收到系统配对码: " + code + " 端口: " + port);
                    unregister();
                    if (callback != null) callback.onPairingCode(code, port);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_WIRELESS_STATUS);
        try {
            context.registerReceiver(receiver, f, "android.permission.MANAGE_DEBUGGING", null);
        } catch (Exception e) {
            // 部分 ROM 的该广播并未强制权限，降级为普通注册
            Log.w(TAG, "带权限注册失败，降级为普通注册", e);
            context.registerReceiver(receiver, f);
        }
    }

    private void unregister() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
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
