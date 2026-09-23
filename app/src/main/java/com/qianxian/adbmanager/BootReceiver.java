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

            // 开机默认关闭 USB 调试：只在这里执行一次。
            // 早先这段挂在 Activity.onCreate 上，导致每次打开界面都把 USB 调试掐断一遍。
            final Context app = context.getApplicationContext();
            final PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    String err = AdbCtl.setUsbAdb(app, false);
                    Log.i(TAG, "========== 开机默认策略：关闭 USB 调试 结果="
                            + (err == null ? "成功" : err) + " ==========");
                } finally {
                    result.finish();
                }
            }).start();
        }
    }
}
