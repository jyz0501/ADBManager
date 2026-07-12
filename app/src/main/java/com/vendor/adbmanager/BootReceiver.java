package com.vendor.adbmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 监听开机完成广播，拉起常驻的 AdbService。
 * 搭配 AndroidManifest 中的 android:persistent="true" 与 RECEIVE_BOOT_COMPLETED 权限使用。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbManager.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "========== 收到开机广播，启动 AdbService ==========");
            Intent svc = new Intent(context, AdbService.class);
            context.startService(svc);
        }
    }
}
