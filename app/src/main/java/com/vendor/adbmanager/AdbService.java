package com.vendor.adbmanager;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻后台服务：
 *  - 开机（或被系统拉起）后，根据用户在 MainActivity 中保存的偏好自动恢复无线 ADB。
 *  - 返回 START_STICKY，进程被杀死后系统会尝试重启本服务；
 *    配合 AndroidManifest 的 android:persistent="true" 实现强常驻。
 */
public class AdbService extends Service {
    private static final String TAG = "AdbManager.Service";
    private static final String PREFS = "adb_prefs";
    private static final String KEY_AUTO_WIRELESS = "auto_wireless_adb";
    private static final int WIRELESS_PORT = 5555;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "========== AdbService 启动（常驻）==========");

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean autoWireless = sp.getBoolean(KEY_AUTO_WIRELESS, false);
        if (autoWireless) {
            executor.submit(() -> {
                Log.i(TAG, "根据保存的偏好自动开启无线 ADB");
                setWirelessAdb(true);
            });
        }
        // 被异常杀死后系统会尝试重启本服务（不重传 intent）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
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
