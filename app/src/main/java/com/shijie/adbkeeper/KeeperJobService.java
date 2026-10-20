package com.shijie.adbkeeper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 开机自愈的第三条腿（也是实测最靠得住的一条）。
 *
 * 原理：JobScheduler 的 persisted job 会被写进 /data/system/job/，重启后由
 * system_server 的 JobSchedulerService 主动重新调度 —— 它不走 BOOT_COMPLETED
 * 广播通道，所以厂商那种「拦第三方 App 开机广播」的手段拦不住它。
 *
 * 做法：跑一次就把 adb_enabled 拉回 1，然后立刻给自己排下一次（persisted），
 * 形成永久自循环；另外再挂一个 15 分钟的周期性 persisted job 做兜底。
 */
public class KeeperJobService extends JobService {
    private static final String TAG = "AdbKeeper";
    private static final int JOB_SELF = 4001;
    private static final int JOB_PERIODIC = 4002;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "job fire id=" + params.getJobId());
        KeeperUtil.restore(getApplicationContext());
        KeeperService.kick(getApplicationContext());
        scheduleSelf(getApplicationContext());
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** 排下一次「尽快执行」的持久化任务。 */
    static void scheduleSelf(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_SELF,
                    new ComponentName(ctx, KeeperJobService.class))
                    .setMinimumLatency(30000L)          // 30 秒后再来一次
                    .setOverrideDeadline(60000L);       // 最迟 60 秒
            if (Build.VERSION.SDK_INT >= 21) b.setPersisted(true);
            js.schedule(b.build());
        } catch (Throwable t) {
            Log.w(TAG, "scheduleSelf failed", t);
        }
    }

    /** 周期性兜底：即使自循环被系统清掉，15 分钟一轮也能救回来。 */
    static void schedulePeriodic(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_PERIODIC,
                    new ComponentName(ctx, KeeperJobService.class))
                    .setPeriodic(15 * 60 * 1000L);
            if (Build.VERSION.SDK_INT >= 21) b.setPersisted(true);
            js.schedule(b.build());
        } catch (Throwable t) {
            Log.w(TAG, "schedulePeriodic failed", t);
        }
    }

    /** 装好后第一次调用：把两条 legs 都架上。 */
    static void arm(Context ctx) {
        scheduleSelf(ctx);
        schedulePeriodic(ctx);
    }
}
