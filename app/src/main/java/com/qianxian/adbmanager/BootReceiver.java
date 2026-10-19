package com.qianxian.adbmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbManager.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "========== 收到开机广播，启动 AdbService ==========");
            Intent svc = new Intent(context, AdbService.class);
            // Android 8+ 起后台 startService 会被限制甚至抛 IllegalStateException，
            // 统一用前台启动：AdbService 会在 onCreate 里立即 startForeground
            context.startForegroundService(svc);

            // USB 调试不再开机强制关闭：与原生开发者选项一致，ADB_ENABLED 由系统持久化保存，
            // 开机后由系统自己按保存的设置恢复（含 composition 与 adbd）。
            // 这里只负责拉起保活服务，不再改动调试开关。
            Log.i(TAG, "USB 调试状态交由系统持久化恢复，本应用不再干预");
        }
    }
}
