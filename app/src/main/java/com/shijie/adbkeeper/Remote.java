package com.shijie.adbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 点击/滑动注入，全部走无障碍服务的 dispatchGesture()。
 *
 * 为什么不用 `input tap`（Runtime.exec）：
 *   App 进程是普通 uid，没有 INJECT_EVENTS 权限，exec 出去的 input 命令同样没有，
 *   会被系统直接拒绝。无障碍服务的 dispatchGesture 是系统唯一允许第三方 App
 *   注入触摸的正规通道，不需要 root、不需要 adb。
 *
 * 前提：无障碍服务已经被系统绑定。绑定由 system_server 的 AccessibilityManagerService
 * 按 settings 里持久化的 enabled_accessibility_services 列表做，所以只要列表里写了、
 * 服务连上了，重启后也会自动恢复。
 */
final class Remote {

    private static final String TAG = "AdbKeeper";

    /** 当前连上的无障碍服务实例，UNAVAILABLE 时为 null。 */
    private static volatile AccessibilityService svc;

    private Remote() {}

    static void attach(AccessibilityService s) {
        svc = s;
        Log.i(TAG, "Remote attached");
    }

    static void detach() {
        svc = null;
        Log.i(TAG, "Remote detached");
    }

    static boolean canInject() {
        return svc != null;
    }

    /** 给 Consent / ScreenCapture 之外的地方取当前实例用，可能为 null。 */
    static AccessibilityService service() {
        return svc;
    }

    // ------------------------------------------------------------------ 手势

    /** 点一下。坐标是电视真实屏幕像素（1920x1080）。 */
    static boolean tap(Context ctx, int x, int y) {
        if (x < 0 || y < 0) return false;
        return gesture(ctx, x, y, x, y, 40L, "tap " + x + "," + y);
    }

    /** 长按。 */
    static boolean longPress(Context ctx, int x, int y, int ms) {
        if (x < 0 || y < 0) return false;
        long d = ms > 0 ? Math.min(6000L, Math.max(400L, ms)) : 700L;
        return gesture(ctx, x, y, x, y, d, "long " + x + "," + y + " " + d + "ms");
    }

    /** 滑动。 */
    static boolean swipe(Context ctx, int x1, int y1, int x2, int y2, int ms) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return false;
        long d = ms > 0 ? Math.min(4000L, Math.max(80L, ms)) : 300L;
        return gesture(ctx, x1, y1, x2, y2, d, "swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    private static boolean gesture(Context ctx, int x1, int y1, int x2, int y2,
                                  long durationMs, String what) {
        AccessibilityService s = svc;
        if (s == null) {
            Log.w(TAG, "gesture rejected, a11y not attached: " + what);
            // 没连上就踢一次守护，让它去把无障碍入口重新登记
            KeeperService.kick(ctx);
            return false;
        }
        if (Build.VERSION.SDK_INT < 24) {
            Log.w(TAG, "dispatchGesture needs API 24+");
            return false;
        }
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0L, durationMs));
            final String label = what;
            boolean ok = s.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    Log.i(TAG, "gesture ok: " + label);
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    Log.w(TAG, "gesture cancelled: " + label);
                }
            }, new Handler(Looper.getMainLooper()));
            if (!ok) Log.w(TAG, "dispatchGesture returned false: " + what);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "gesture failed: " + what, t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 全局按键

    /** 主页 / 返回 / 最近任务 / 通知栏，走无障碍的 performGlobalAction。 */
    static boolean globalAction(Context ctx, String name) {
        AccessibilityService s = svc;
        if (s == null) {
            Log.w(TAG, "globalAction rejected, a11y not attached: " + name);
            KeeperService.kick(ctx);
            return false;
        }
        int id;
        if ("home".equals(name)) {
            id = AccessibilityService.GLOBAL_ACTION_HOME;
        } else if ("back".equals(name)) {
            id = AccessibilityService.GLOBAL_ACTION_BACK;
        } else if ("recents".equals(name)) {
            id = AccessibilityService.GLOBAL_ACTION_RECENTS;
        } else if ("notifications".equals(name)) {
            id = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        } else if ("power".equals(name)) {
            id = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
        } else {
            return false;
        }
        try {
            boolean ok = s.performGlobalAction(id);
            Log.i(TAG, "globalAction " + name + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "globalAction failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 启动应用

    /**
     * 启动某个应用。
     *
     * 两条路：
     *  1) 无障碍服务已连上：优先用它当前所在的窗口管理器抛出的 launch intent
     *     （这里其实用的是 Context.startActivity，需要 NEW_TASK）。
     *  2) 没连上：仍用 Context.startActivity，Android 9 上第三方 App 后台启动
     *     Activity 通常被允许（限制是 Android 10 才开始收紧）。
     */
    static boolean open(Context ctx, String pkg, String act) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Intent i;
            if (act != null && !act.isEmpty()) {
                i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, act));
            } else {
                i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (i == null) {
                Log.w(TAG, "no launch intent for " + pkg);
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log.i(TAG, "open " + pkg + "/" + act);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "open failed " + pkg, t);
            return false;
        }
    }

    /** 读当前前台窗口的包名，仅用于日志排查。 */
    static String currentPackage() {
        return null;
    }

    static AccessibilityNodeInfo root() {
        AccessibilityService s = svc;
        if (s == null) return null;
        try {
            return s.getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
    }
}
