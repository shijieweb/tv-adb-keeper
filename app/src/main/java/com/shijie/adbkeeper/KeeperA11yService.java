package com.shijie.adbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务，唯一作用：给这个 App 一个「开机时 system_server 会主动绑定」的入口。
 *
 * 背景：MiTV 的 ROM 拦截了第三方 App 的 BOOT_COMPLETED（实测重启后 BootReceiver 从未被调用，
 * 进程根本没起来），但 AccessibilityManagerService 是按 settings 里持久化的
 * enabled_accessibility_services 列表在开机时由 system_server 主动绑定的。
 *
 * 所以：开机 → 系统绑定本服务 → onServiceConnected 里立刻把 adb_enabled 写回 1，
 * 并拉起 KeeperService 做持续守护。
 */
public class KeeperA11yService extends AccessibilityService {
    private static final String TAG = "AdbKeeper";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "a11y connected -> boot hook fired");
        KeeperUtil.restore(getApplicationContext());
        KeeperService.kick(getApplicationContext());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 每次界面变化顺手检查一次，几乎不耗电，多一个触发点
        KeeperUtil.restore(getApplicationContext());
    }

    @Override
    public void onInterrupt() {
    }
}
