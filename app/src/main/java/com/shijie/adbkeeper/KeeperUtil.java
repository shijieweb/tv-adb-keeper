package com.shijie.adbkeeper;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** 把 adb_enabled 拉回 1 的公共逻辑，供 Service / 无障碍服务 / JobService 共用。 */
final class KeeperUtil {
    private static final String TAG = "AdbKeeper";

    private static volatile long lastCheckAt = 0L;

    private KeeperUtil() {}

    static boolean hasWriteSecure(Context ctx) {
        return ctx.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static int currentAdb(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.ADB_ENABLED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 读系统属性（android.os.SystemProperties 是隐藏类，用反射读，只读不改）。 */
    static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 基础动作：adb_enabled 是 0 就写回 1。
     *
     * @return true 表示本次真的写了
     */
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
            Log.i(TAG, (ok ? "adb_enabled 0 -> 1 restored" : "write adb_enabled failed")
                    + ", adbd=" + prop("init.svc.adbd"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "restore failed", t);
            return false;
        }
    }

    /**
     * 完整自愈：只做「把 adb_enabled 拉回 1」这一件事。
     *
     * 为什么不做 0 -> 1 的「踢」（bounce）：
     *   1) 不需要。v1 实测：厂商写完 0 之后我们再写 1，system_server 的 AdbService
     *      观察者会看到值变化并重新拉起 adbd，800ms 内 5555 就 OPEN 了。
     *   2) 有害。踢动会真的断开正在用的 ADB 连接，而「判断 adbd 是否活着」在 App 里
     *      只有隐藏属性反射（targetSdk 28 会被非 SDK 接口限制挡掉）和 loopback 端口
     *      探测（adbd 可能只监听 IPv6）两种不可靠手段 —— 判错一次就会每分钟把老板
     *      正在用的连接踹掉一次。宁可少做，不可误伤。
     *
     * 所以只保留 restore()，不做任何 adbd 健康判断。
     */
    static void ensure(Context ctx) {
        lastCheckAt = SystemClock.elapsedRealtime();
        restore(ctx);
    }

    /** 高频回调（无障碍事件）里用：10 秒内不重复检查，避免每条 UI 事件都读一次设置。 */
    static void ensureThrottled(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheckAt < 10_000L) return;
        lastCheckAt = now;
        ensure(ctx);
    }

    /**
     * 把自己写进 enabled_accessibility_services（保留系统里已有的其它服务）。
     * 这样开机时 system_server 的 AccessibilityManagerService 会主动绑定我们，
     * 绕开厂商对第三方 App 自启动（BOOT_COMPLETED）的拦截。
     */
    static boolean registerSelfAsA11y(Context ctx) {
        if (!hasWriteSecure(ctx)) {
            Log.w(TAG, "registerSelfAsA11y: no permission");
            return false;
        }
        try {
            ComponentName me = new ComponentName(ctx, KeeperA11yService.class);
            String flat = me.flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            boolean already = cur.contains(flat)
                    || cur.contains("com.shijie.adbkeeper/.KeeperA11yService");
            if (already) {
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

    /** 本 App 的无障碍服务是否已在系统的启用列表里。 */
    static boolean a11yRegistered(Context ctx) {
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) return false;
            String flat = new ComponentName(ctx, KeeperA11yService.class).flattenToString();
            return cur.contains(flat)
                    || cur.contains("com.shijie.adbkeeper/.KeeperA11yService");
        } catch (Throwable t) {
            return false;
        }
    }
}
