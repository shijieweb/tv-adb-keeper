package com.shijie.adbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 自动点掉系统的「投屏授权」弹窗。
 *
 * 为什么必须有它：Android 9 的 MediaProjection 授权是「一次性」的，进程重启就失效，
 * 每次开机第一次要看画面时系统都会弹「将开始截取您的屏幕上显示的所有内容 / 立即开始」。
 * 老板要的是「打开网页就能看到画面」，绝不可能每次都跑去电视上按遥控器，所以必须自动点。
 *
 * 触发方式有两条（互为备份，任一都够）：
 *   1) 无障碍事件里发现弹窗（KeeperA11yService.onAccessibilityEvent）
 *   2) 守护服务的 3 秒心跳里发现有等待中的授权（KeeperService.tick → clickIfNeeded）
 * 这样即使某次窗口事件没派发过来，3 秒内也会补点。
 */
final class Consent {

    private static final String TAG = "AdbKeeper";

    /** 确认按钮的候选文案：不同 ROM/系统版本叫法不一样，全兜住。 */
    private static final String[] TEXTS = {
            "立即开始", "开始录制", "开始投屏", "开始", "允许", "确定"
    };

    /** 上一次点击的时间，避免同一次弹窗被连点（会让系统当成异常输入）。 */
    private static volatile long lastClickAt = 0L;

    private Consent() {}

    /** 返回 true 表示已经点过（或刚点过），调用方不用再重试。 */
    static boolean clickIfNeeded(AccessibilityService s) {
        if (s == null) return false;
        if (System.currentTimeMillis() - lastClickAt < 1500L) return true;
        AccessibilityNodeInfo root;
        try {
            root = s.getRootInActiveWindow();
        } catch (Throwable t) {
            Log.w(TAG, "consent root: " + t);
            return false;
        }
        if (root == null) return false;
        try {
            for (String text : TEXTS) {
                List<AccessibilityNodeInfo> hits;
                try {
                    hits = root.findAccessibilityNodeInfosByText(text);
                } catch (Throwable t) {
                    continue;
                }
                if (hits == null) continue;
                for (AccessibilityNodeInfo n : hits) {
                    if (n == null) continue;
                    CharSequence cs = n.getText();
                    if (cs == null) continue;
                    // findAccessibilityNodeInfosByText 是子串匹配，必须精确比对，
                    // 否则「立即开始」会命中说明文字「将开始截取…」里的片段
                    if (!text.equals(cs.toString().trim())) continue;
                    AccessibilityNodeInfo target = findClickable(n);
                    if (target == null) continue;
                    boolean ok;
                    try {
                        ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } catch (Throwable t) {
                        continue;
                    }
                    Log.i(TAG, "consent click '" + text + "' -> " + ok);
                    if (ok) {
                        lastClickAt = System.currentTimeMillis();
                        return true;
                    }
                }
            }
        } finally {
            // root 是系统持有的，不要 recycle
        }
        return false;
    }

    /** 自己不可点就往上找可点的祖先（系统弹窗常常是外层容器可点）。 */
    private static AccessibilityNodeInfo findClickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            try {
                if (cur.isClickable()) return cur;
                cur = cur.getParent();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
