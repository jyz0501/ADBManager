package com.vendor.adbmanager;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 常驻后台服务：
 *  - 开机（或被系统拉起）后，根据用户在 MainActivity 中保存的偏好自动恢复无线 ADB。
 *  - 持续轮询 USB ADB 开关 与 无线 ADB 端口,状态变化时 Toast 提示。
 *  - 返回 START_STICKY，进程被杀死后系统会尝试重启本服务；
 *    配合 AndroidManifest 的 android:persistent="true" 实现强常驻。
 */
public class AdbService extends Service {
    private static final String TAG = "AdbManager.Service";
    private static final String PREFS = "adb_prefs";
    private static final String KEY_AUTO_WIRELESS = "auto_wireless_adb";
    private static final int WIRELESS_PORT = 5555;
    private static final int POLL_INTERVAL_SECONDS = 2;
    /** 轮询启动前的延迟,等待本服务自己的 setWirelessAdb() 完成,避免误触发 Toast */
    private static final int POLL_INITIAL_DELAY_SECONDS = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService pollExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 上一次状态:-1=未初始化 0=关 1=开
    private int lastUsbAdbState = -1;
    // 上一次无线 ADB 端口,"-1"/"0"/"" 视为关闭
    private String lastWirelessPort = "";
    // 首次轮询只建立基线,不弹 Toast
    private boolean baselineEstablished = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "========== AdbService 启动（常驻）==========");

        // 默认策略：ADB 保持关闭。开机/服务拉起时不自动开启任何 ADB。
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean autoWireless = sp.getBoolean(KEY_AUTO_WIRELESS, false);
        if (autoWireless) {
            // 仅当用户之前手动开启了无线 ADB，才恢复（如不需要可删除此分支）
            executor.submit(() -> {
                Log.i(TAG, "根据保存的偏好自动开启无线 ADB");
                setWirelessAdb(true);
            });
        } else {
            // 确保 ADB 默认处于关闭状态
            executor.submit(() -> {
                Log.i(TAG, "默认策略：确保 ADB 处于关闭状态");
                setWirelessAdb(false);
            });
        }

        // 启动状态轮询(只启动一次)
        startAdbStatePolling();

        // 被异常杀死后系统会尝试重启本服务（不重传 intent）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    /** 启动轮询:每 2 秒检查 USB ADB 开关与无线 ADB 端口,变化时 Toast 提示。
     *  延迟 3 秒启动,首次轮询只建立基线不弹 Toast,避免开机时本服务自身的 setWirelessAdb(false)
     *  动作被监听器误判为"用户关闭"而弹 Toast。 */
    private void startAdbStatePolling() {
        if (pollExecutor != null) return;
        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        pollExecutor.scheduleAtFixedRate(() -> {
            try {
                int currentUsb = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                String currentPort = readProp("service.adb.tcp.port");

                if (baselineEstablished) {
                    // USB ADB 开关变化
                    if (lastUsbAdbState != currentUsb) {
                        String msg = currentUsb == 1 ? "USB ADB 已开启" : "USB ADB 已关闭";
                        Log.i(TAG, "状态变化: " + msg);
                        showToast(msg);
                    }
                    // 无线 ADB 端口变化
                    boolean wasOn = isPortOn(lastWirelessPort);
                    boolean nowOn = isPortOn(currentPort);
                    if (wasOn != nowOn) {
                        String msg = nowOn ? "无线 ADB 已开启" : "无线 ADB 已关闭";
                        Log.i(TAG, "状态变化: " + msg + "(port=" + currentPort + ")");
                        showToast(msg);
                    }
                }

                lastUsbAdbState = currentUsb;
                lastWirelessPort = currentPort;
                baselineEstablished = true;
            } catch (Exception e) {
                Log.e(TAG, "轮询 ADB 状态失败", e);
            }
        }, POLL_INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isPortOn(String port) {
        return port != null && !port.isEmpty() && !port.equals("-1") && !port.equals("0");
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private String readProp(String prop) {
        try {
            java.lang.Process p = new ProcessBuilder("getprop", prop)
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String value = br.readLine();
            p.waitFor();
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            Log.e(TAG, "readProp 失败: " + prop, e);
            return "";
        }
    }

    private void setWirelessAdb(boolean enabled) {
        try {
            String portValue = enabled ? String.valueOf(WIRELESS_PORT) : "-1";
            executeShellCommand("setprop", "service.adb.tcp.port", portValue);
            executeShellCommand("setprop", "ctl.stop", "adbd");
            Thread.sleep(500);
            executeShellCommand("setprop", "ctl.start", "adbd");
            Thread.sleep(1000);
            Log.i(TAG, "无线 ADB " + (enabled ? "已开启" : "已关闭"));
        } catch (Exception e) {
            Log.e(TAG, "设置无线 ADB 失败", e);
        }
    }

    private int executeShellCommand(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                Log.d(TAG, "命令输出: " + line);
            }
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + java.util.Arrays.toString(args), e);
            return -1;
        }
    }
}
