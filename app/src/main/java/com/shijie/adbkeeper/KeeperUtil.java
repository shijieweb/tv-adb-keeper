package com.shijie.adbkeeper;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/** 把 adb_enabled 拉回 1 的公共逻辑，供 Service / 无障碍服务共用。 */
final class KeeperUtil {
    private static final String TAG = "AdbKeeper";

    private KeeperUtil() {}

    static boolean hasWriteSecure(Context ctx) {
        return ctx.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return true 表示本次真的做了恢复动作 */
    static boolean restore(Context ctx) {
        if (!hasWriteSecure(ctx)) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, skip");
            return false;
        }
        try {
            int cur = Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 1);
            if (cur == 1) return false;
            boolean ok = Settings.Global.putInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 1);
            Log.i(TAG, ok ? "adb_enabled 0 -> 1 restored" : "write adb_enabled failed");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "restore failed", t);
            return false;
        }
    }

    static int currentAdb(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把自己写进 enabled_accessibility_services（保留系统里已有的其它服务）。
     * 这样开机时 system_server 的 AccessibilityManagerService 会主动绑定我们，
     * 绕开厂商对第三方 App 自启动的拦截。
     */
    static boolean registerSelfAsA11y(Context ctx) {
        if (!hasWriteSecure(ctx)) return false;
        try {
            ComponentName me = new ComponentName(ctx, KeeperA11yService.class);
            String flat = me.flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            if (cur.contains(flat)) {
                Log.i(TAG, "already in a11y list");
                return true;
            }
            String next = cur.isEmpty() ? flat : cur + ":" + flat;
            boolean ok = Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            // 顺带把总开关打开，提高开机被绑定的概率（实测总开关不是绑定门槛，但开着更保险）
            try {
                Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (Throwable ignore) {}
            Log.i(TAG, "registerSelfAsA11y " + ok + " -> " + next);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "registerSelfAsA11y failed", t);
            return false;
        }
    }
}
