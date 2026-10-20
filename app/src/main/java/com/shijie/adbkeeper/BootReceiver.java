package com.shijie.adbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播兜底。实测这台 MiTV 的 ROM 会拦第三方 App 的 BOOT_COMPLETED
 * （重启后本 receiver 从未被调用），所以真正生效的开机入口是 KeeperA11yService。
 * 这里保留，能收到就更快一步。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbKeeper";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "boot receiver: " + a);
        KeeperUtil.restore(ctx);
        KeeperService.kick(ctx);
    }
}
