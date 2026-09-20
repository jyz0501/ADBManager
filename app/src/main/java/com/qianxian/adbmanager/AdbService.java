package com.qianxian.adbmanager;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ADB 状态后台服务（前台服务）。
 *
 * 职责演进：
 * 1. 轮询 USB ADB / 无线 ADB 的属性变化并提示（原有行为）；
 * 2. **自动重开**：用户开启过无线 ADB 后，若系统把它重置（熄屏省电、adbd 被杀、
 *    某些 ROM 会自动清理），在本服务存活期间自动把它拉回来；
 * 3. 以前台服务形式常驻：普通后台服务在系统回收时起不来，何谈保活。
 *
 * 注意：所有重启动作都避开「有客户端在线」的时刻，不会掐断正在用的连接。
 */
public class AdbService extends Service {

    private static final String TAG = "AdbManager.Service";

    /** ADB 状态轮询周期。 */
    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final int POLL_INITIAL_DELAY_SECONDS = 3;

    /** 每 N 轮做一次自动修复检查（N × 轮询周期 ≈ 10 秒，内部还会再做节流）。 */
    private static final int REPAIR_CHECK_EVERY = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService pollExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int lastUsbAdbState = -1;
    private String lastWirelessPort = "";
    private boolean baselineEstablished = false;
    private int pollCount = 0;

    private String lastNotiText = "";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "========== AdbService 创建（前台常驻）==========");
        AdbCtl.createChannel(this);
        runCatchingForeground();
        acquireLocks();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "========== AdbService 启动 ==========");
        // 前台服务在系统销毁重建后可能没有走 onCreate，这里补一次
        runCatchingForeground();

        applyWirelessPolicy();
        startAdbStatePolling();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterNetworkCallback();
        releaseLocks();
        super.onDestroy();
        executor.shutdownNow();
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    /** Android 12+ 起后台不能裸 startService，必须尽快切到前台，否则会被系统判为 ANR/停止。 */
    private void runCatchingForeground() {
        try {
            Notification noti = AdbCtl.buildNotification(this, "无线 ADB 状态监听中", null);
            startForeground(AdbCtl.NOTI_ID, noti);
        } catch (Exception e) {
            Log.w(TAG, "切换到前台失败（可能是受限 ROM），继续以普通服务运行: " + e.getMessage());
        }
    }

    /**
     * 冷启动时的无线 ADB 策略：
     * - 用户上次明确开启过 → 恢复（这是「自动重连」的另一半：进程/设备重启后自动复常态）；
     * - 其余情况一律保持关闭（沿用既有的「不记忆状态」安全策略）。
     */
    private void applyWirelessPolicy() {
        executor.submit(() -> {
            boolean desired = AdbCtl.isWirelessDesired(this);
            boolean enabled = AdbCtl.isWirelessOn(this);
            if (desired && !enabled) {
                Log.i(TAG, "检测到上次希望开启无线 ADB，正在恢复");
                AdbCtl.setWirelessAdb(this, true);
            } else if (!desired && enabled) {
                Log.i(TAG, "默认策略：确保 ADB 处于关闭状态");
                AdbCtl.setWirelessAdb(this, false);
            }
        });
    }

    // ---------- 轮询 ----------

    private void startAdbStatePolling() {
        if (pollExecutor != null) return;
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleAtFixedRate(() -> {
            try {
                int currentUsb = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                String currentPort = AdbCtl.effectivePort();

                if (baselineEstablished) {
                    if (lastUsbAdbState != currentUsb) {
                        String msg = currentUsb == 1 ? "USB ADB 已开启" : "USB ADB 已关闭";
                        Log.i(TAG, "状态变化: " + msg);
                        showToast(msg);
                    }

                    boolean wasOn = AdbCtl.isPortEnabled(lastWirelessPort);
                    boolean nowOn = AdbCtl.isPortEnabled(currentPort);
                    if (wasOn != nowOn) {
                        String msg = nowOn ? "无线 ADB 已开启" : "无线 ADB 已关闭";
                        Log.i(TAG, "状态变化: " + msg + "(port=" + currentPort + ")");
                        showToast(msg);
                    }
                }

                lastUsbAdbState = currentUsb;
                lastWirelessPort = currentPort;
                baselineEstablished = true;

                maybeRepair(currentUsb);
                updateNotification(currentPort);
            } catch (Exception e) {
                Log.e(TAG, "轮询 ADB 状态失败", e);
            }
        }, POLL_INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 兜底修复：USB ADB 开着但 adbd 没了就拉起；
     * 用户希望无线 ADB 开着、但它已经失效（属性被重置 / adbd 不在 / 端口没监听）就重新开启。
     */
    private void maybeRepair(int currentUsb) {
        pollCount++;
        if (pollCount % REPAIR_CHECK_EVERY != 0) return;
        executor.submit(() -> {
            try {
                if (currentUsb == 1 && !AdbCtl.isAdbdAlive()) {
                    Log.w(TAG, "USB ADB 为开启状态但 adbd 已退出，正在拉起");
                    AdbCtl.startAdbd();
                }
                if (AdbCtl.isWirelessDesired(this)) {
                    AdbCtl.repairIfNeeded(this);
                }
            } catch (Exception e) {
                Log.w(TAG, "自动修复异常", e);
            }
        });
    }

    private void updateNotification(String port) {
        String text;
        boolean desired = AdbCtl.isWirelessDesired(this);
        if (AdbCtl.isPortEnabled(port) && desired) {
            text = "无线 ADB 已开启：" + port;
        } else if (desired) {
            text = "无线 ADB 待恢复（掉线后会自动重开）";
        } else {
            text = "无线 ADB 未开启";
        }
        if (text.equals(lastNotiText)) return;
        lastNotiText = text;
        try {
            Notification noti = AdbCtl.buildNotification(this, text, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(AdbCtl.NOTI_ID, noti);
        } catch (Exception e) {
            Log.w(TAG, "更新通知失败", e);
        }
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------- 保活手段 ----------

    /**
     * 熄屏后 CPU 休眠与 Wi-Fi 休眠是无线 ADB 掉线的主因。
     * 本应用是 platform 签名的系统应用（常供电车机），持有锁可接受。
     */
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdbManager:AdbService");
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "获取 CPU 锁失败", e);
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AdbManager:AdbService");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "获取 Wi-Fi 锁失败", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception e) {
            Log.w(TAG, "释放锁失败", e);
        }
        wakeLock = null;
        wifiLock = null;
    }

    /** 网络回来（Wi-Fi 从休眠恢复/重连）时立刻做一次修复检查。 */
    private void registerNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "网络已恢复，立即检查无线 ADB");
                    maybeRepair(Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0));
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "注册网络监听失败", e);
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "注销网络监听失败", e);
        }
        connectivityManager = null;
        networkCallback = null;
    }
}
