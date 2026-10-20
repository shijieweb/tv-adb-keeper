package com.shijie.adbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** 开机自启：拉起守护服务。 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbKeeper";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "boot receiver: " + a);
        try {
            Intent k = new Intent(ctx, KeeperService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(k);
            } else {
                ctx.startService(k);
            }
        } catch (Throwable t) {
            Log.w(TAG, "boot start failed", t);
        }
    }
}
