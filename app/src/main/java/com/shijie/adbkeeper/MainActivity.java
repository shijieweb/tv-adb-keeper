package com.shijie.adbkeeper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#101418"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(28);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        root.addView(title("ADB 常开", 34, "#FFFFFF"));
        root.addView(title("这个应用只做一件事：让这台电视的 ADB 调试开关永远保持打开，\n"
                + "这样重启之后你的手机/电脑浏览器遥控还能直接用，不用再进设置里手动开。", 16, "#9AA6B2"));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        status.setTextColor(Color.parseColor("#E6EDF3"));
        status.setPadding(0, dp(24), 0, dp(24));
        root.addView(status);

        root.addView(button("立即检查并开启一次", new View.OnClickListener() {
            @Override public void onClick(View v) { KeeperService.kick(getApplicationContext()); }
        }));
        root.addView(button("启动后台守护", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, KeeperService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            }
        }));
        root.addView(button("加入电池白名单（防止被系统杀掉）", new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        }));
        root.addView(button("注册开机自愈入口（关键，只需一次）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = KeeperUtil.registerSelfAsA11y(getApplicationContext());
                toast(ok ? "已注册：重启后系统会自动拉起守护" : "注册失败，请确认写入权限已授予");
                refresh();
            }
        }));
        root.addView(button("打开开发者选项页", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    toast("这台电视没有标准开发者选项页");
                }
            }
        }));

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
        // 每次打开界面：确保守护在跑 + 确保开机自愈入口在册（幂等）
        KeeperUtil.registerSelfAsA11y(getApplicationContext());
        Intent i = new Intent(this, KeeperService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void refresh() {
        if (status == null) return;
        boolean granted = hasPerm();
        int v = -1;
        try {
            v = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (Exception ignore) {}

        StringBuilder sb = new StringBuilder();
        sb.append("ADB 写入权限：").append(granted ? "已授予 ✓" : "未授予 ✗").append('\n');
        sb.append("当前 ADB 开关：").append(v == 1 ? "开 ✓" : (v == 0 ? "关 ✗" : "读不到")).append('\n');
        sb.append("开机自愈入口：").append(a11yRegistered() ? "已注册 ✓" : "未注册 ✗");
        if (!granted) {
            sb.append("\n\n需要先给一次权限（只做一次，重启不会丢）：\n\n");
            sb.append("adb shell pm grant com.shijie.adbkeeper android.permission.WRITE_SECURE_SETTINGS");
        }
        if (granted && !a11yRegistered()) {
            sb.append("\n\n点下面「注册开机自愈入口」按钮即可（只需一次）。");
        }
        status.setText(sb.toString());
    }

    /** 检查本App的无障碍服务是否已写进系统的 enabled_accessibility_services 列表。 */
    private boolean a11yRegistered() {
        try {
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) return false;
            String flat = new android.content.ComponentName(this, KeeperA11yService.class)
                    .flattenToString();
            return cur.contains(flat) || cur.contains("com.shijie.adbkeeper/.KeeperA11yService");
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasPerm() {
        return checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已经在白名单里了");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("系统没有这个页面：" + e.getMessage());
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView title(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.START);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.bottomMargin = dp(14);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
