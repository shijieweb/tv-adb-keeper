package com.shijie.adbkeeper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * 只为把系统的「将开始截取您的屏幕上显示的所有内容」授权框顶出来。
 *
 * 自己完全透明、没有布局，拿到结果立刻把自己关掉，老板在电视上不会感觉它存在。
 * 那个授权框里的「立即开始」由 KeeperA11yService 用无障碍自动点掉
 * （Android 9 没有「以后不再询问」，每次开机都得点一次，所以必须自动化）。
 */
public class ProjectionRequestActivity extends Activity {

    private static final String TAG = "AdbKeeper";
    private static final int REQ = 0x5C2E;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Throwable t) {
            Log.w(TAG, "createScreenCaptureIntent: " + t);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ) return;
        Log.i(TAG, "consent result=" + result + " hasData=" + (data != null));
        if (result == RESULT_OK && data != null) {
            ScreenCapture.setPermission(result, data);
            boolean ok = ScreenCapture.get(getApplicationContext()).start();
            Log.i(TAG, "projection start after consent: " + ok);
        }
        finish();
    }
}
