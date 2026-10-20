package com.shijie.adbkeeper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 把电视画面抓成 JPEG，给网页触控板当「眼睛」。
 *
 * 为什么只能走 MediaProjection：这台 MiTV-ANSP0（Android 9 / SDK 28）上所有别的路都堵死了 ——
 *   /dev/graphics/fb0 是 crw-rw---- root graphics，App 读不到；
 *   READ_FRAME_BUFFER 保护级是 signature|privileged（不带 development 位），pm grant 给不了；
 *   用 App 自己的 uid 跑 screencap 会得到 0 字节 png（SurfaceFlinger 直接拒）；
 *   AccessibilityService.takeScreenshot() 要 API 30，这台是 28。
 * 所以唯一活路是 MediaProjection（它由 system_server 授权，不需要任何特权权限）。
 *
 * 代价：Android 9 没有「永久授权」，每次开机都要重新弹一次授权框。
 * 对策：ProjectionRequestActivity 发起授权，KeeperA11yService 用无障碍把弹窗里的
 *      「立即开始」自动点掉 —— 老板全程无感。
 *
 * 省电/省内存的两个设计：
 *   1) 抓帧只在有人看的时候编码（activeUntil 窗口 5 秒）。没人看时只 acquire+close，
 *      不压 JPEG，Cortex-A55 上几乎零开销。
 *   2) 输出降到 1280x720（面板是 1080p，缩一半文字仍清楚），JPEG 质量 60，
 *      每帧约 60~100KB，2.4G / 72Mbps 的 WiFi 上 2~3 fps 毫无压力。
 */
final class ScreenCapture {

    private static final String TAG = "AdbKeeper";

    /** 抓帧输出尺寸（1920x1080 的一半宽，等比）。 */
    static final int OUT_W = 1280;
    static final int OUT_H = 720;
    /** 对应密度：原屏 1920 宽 / 320dpi，缩到 1280 宽就是 213dpi，否则虚拟屏上的字会巨大。 */
    private static final int OUT_DPI = 213;
    private static final int JPEG_QUALITY = 60;
    /** 没人看画面时，超过这个时间就不再压 JPEG。 */
    private static final long ACTIVE_WINDOW_MS = 5000L;

    private static volatile ScreenCapture instance;

    /** 授权结果必须由一个 Activity 拿到，暂存在这里给 start() 用。静态的，进程内有效。 */
    private static volatile int permResultCode = 0;
    private static volatile Intent permData = null;
    /** 是否正等着系统弹「立即开始」授权框（给无障碍自动点按钮用）。 */
    private static volatile long consentRequestedAt = 0L;

    private final Context app;

    private volatile MediaProjection projection;
    private volatile VirtualDisplay display;
    private volatile ImageReader reader;
    private HandlerThread thread;
    private Handler handler;

    private volatile byte[] jpeg;
    private volatile long jpegAt;
    private volatile long activeUntil;
    private volatile boolean started;

    private final Object frameLock = new Object();

    private ScreenCapture(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    static ScreenCapture get(Context ctx) {
        if (instance == null) {
            synchronized (ScreenCapture.class) {
                if (instance == null) instance = new ScreenCapture(ctx);
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------ 授权

    /** ProjectionRequestActivity 拿到结果后回调这里。 */
    static void setPermission(int resultCode, Intent data) {
        permResultCode = resultCode;
        permData = data;
        consentRequestedAt = 0L;
        Log.i(TAG, "projection permission result=" + resultCode + " data=" + (data != null));
    }

    static boolean hasPermission() {
        return permResultCode != 0 && permData != null;
    }

    /** 是否正在等授权框（无障碍据此决定要不要去点「立即开始」）。 */
    static boolean awaitingConsent() {
        long t = consentRequestedAt;
        return t > 0 && SystemClock.elapsedRealtime() - t < 20000L;
    }

    /** 拉起授权流程：透明 Activity 把系统弹窗顶出来，无障碍负责点掉。 */
    static void requestPermission(Context ctx) {
        final Context app = ctx.getApplicationContext();
        consentRequestedAt = SystemClock.elapsedRealtime();
        // 统一回主线程，避免从 HTTP 连接线程直接 startActivity
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(app, ProjectionRequestActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    Log.i(TAG, "projection permission requested");
                } catch (Throwable t) {
                    Log.w(TAG, "requestPermission: " + t);
                    consentRequestedAt = 0L;
                }
            }
        });
    }

    // ------------------------------------------------------------------ 启停

    boolean isStreaming() {
        return started && reader != null && jpeg != null;
    }

    boolean isStarted() {
        return started;
    }

    /**
     * 用已拿到的授权建虚拟屏。幂等：已经在跑就直接返回 true。
     * 授权没了（比如刚重启）返回 false，调用方应改走 requestPermission()。
     */
    boolean start() {
        synchronized (ScreenCapture.class) {
            if (started && reader != null) return true;
            if (!hasPermission()) {
                Log.w(TAG, "start: no permission yet");
                return false;
            }
            stopInternal();
            try {
                thread = new HandlerThread("tv-screen");
                thread.start();
                handler = new Handler(thread.getLooper());

                MediaProjectionManager mpm = (MediaProjectionManager)
                        app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection p = mpm.getMediaProjection(permResultCode, permData);
                if (p == null) {
                    Log.w(TAG, "getMediaProjection returned null (permission consumed?)");
                    permResultCode = 0;
                    permData = null;
                    return false;
                }
                p.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.i(TAG, "projection stopped by system");
                        synchronized (ScreenCapture.class) {
                            stopInternal(true);
                        }
                    }
                }, handler);

                ImageReader r = ImageReader.newInstance(OUT_W, OUT_H,
                        PixelFormat.RGBA_8888, 2);
                r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader ir) {
                        drainAndMaybeEncode(ir);
                    }
                }, handler);

                VirtualDisplay vd = p.createVirtualDisplay("tv-screen",
                        OUT_W, OUT_H, OUT_DPI,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        r.getSurface(), null, handler);

                projection = p;
                reader = r;
                display = vd;
                started = true;
                jpeg = null;
                jpegAt = 0L;
                // 先打开 5 秒窗口，让第一帧能编码出来
                activeUntil = SystemClock.elapsedRealtime() + ACTIVE_WINDOW_MS;
                Log.i(TAG, "projection started " + OUT_W + "x" + OUT_H);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "start: " + t);
                stopInternal();
                return false;
            }
        }
    }

    void stop() {
        synchronized (ScreenCapture.class) {
            stopInternal();
        }
    }

    private void stopInternal() {
        stopInternal(false);
    }

    /**
     * @param fromCallback true = 是系统的 onStop 回调里进来的，别再回头调 projection.stop()
     *                     （会在同一线程里递归自己，直接 StackOverflow 或卡死）。
     */
    private void stopInternal(boolean fromCallback) {
        started = false;
        jpeg = null;
        jpegAt = 0L;
        try {
            if (display != null) display.release();
        } catch (Throwable ignore) {}
        display = null;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignore) {}
        reader = null;
        if (!fromCallback) {
            try {
                if (projection != null) projection.stop();
            } catch (Throwable ignore) {}
        }
        projection = null;
        // 回调本身就跑在 thread 上，自己退出自己会抛异常，留给 GC
        if (!fromCallback) {
            try {
                if (thread != null) thread.quitSafely();
            } catch (Throwable ignore) {}
            thread = null;
            handler = null;
        }
    }

    // ------------------------------------------------------------------ 抓帧

    /**
     * 取一帧 JPEG。没有新帧时会等最多 maxWaitMs 毫秒。
     * 调用它就等于「有人在看」，会打开 5 秒的编码窗口。
     */
    byte[] frame(int maxWaitMs) {
        activeUntil = SystemClock.elapsedRealtime() + ACTIVE_WINDOW_MS;
        if (!started) {
            // 没在跑：先试着起（有授权就能直接起来），起不来就重走一次授权流程。
            // 开机后第一次被访问一定会走这条路（Android 9 的授权不跨进程）。
            if (!start() && !awaitingConsent()) {
                requestPermission(app);
            }
            return null;
        }
        long deadline = SystemClock.elapsedRealtime() + maxWaitMs;
        synchronized (frameLock) {
            while (true) {
                byte[] cur = jpeg;
                long age = SystemClock.elapsedRealtime() - jpegAt;
                if (cur != null && age < 1500L) return cur;
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return cur;
                try {
                    frameLock.wait(left);
                } catch (InterruptedException e) {
                    return jpeg;
                }
            }
        }
    }

    /** ImageReader 有图时：总是取走并关掉（保持管线流动），只有窗口期内才压 JPEG。 */
    private void drainAndMaybeEncode(ImageReader ir) {
        Image img = null;
        try {
            img = ir.acquireLatestImage();
            if (img == null) return;
            if (SystemClock.elapsedRealtime() > activeUntil) return;   // 没人看，跳过编码
            byte[] out = toJpeg(img);
            if (out != null) {
                jpeg = out;
                jpegAt = SystemClock.elapsedRealtime();
                synchronized (frameLock) {
                    frameLock.notifyAll();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "drain: " + t);
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Throwable ignore) {}
            }
        }
    }

    private byte[] toJpeg(Image img) {
        Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int w = img.getWidth();
        int h = img.getHeight();
        int rowPadding = rowStride - pixelStride * w;

        Bitmap bmp = null;
        try {
            // 行有 padding 时，位图要按 (rowStride/pixelStride) 宽建，再裁掉右边
            int bw = w + rowPadding / pixelStride;
            bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            Bitmap use = bmp;
            if (bw != w) {
                use = Bitmap.createBitmap(bmp, 0, 0, w, h);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            use.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            if (use != bmp) use.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "toJpeg: " + t);
            return null;
        } finally {
            if (bmp != null) {
                try {
                    bmp.recycle();
                } catch (Throwable ignore) {}
            }
        }
    }
}
