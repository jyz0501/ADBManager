package com.vendor.adbmanager;

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
            context.startService(svc);
        }
    }
}
