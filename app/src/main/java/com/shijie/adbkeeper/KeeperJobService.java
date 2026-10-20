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
 * 开机自愈的第二条腿（走 JobScheduler，不走广播）。
 *
 * 原理：persisted job 会被写进 /data/system/job/，重启后由 system_server 的
 * JobSchedulerService 主动重新调度 —— 它不走 BOOT_COMPLETED 广播通道，
 * 所以厂商那种「拦第三方 App 开机广播」的手段拦不住它。
 *
 * 三条任务：
 *  - JOB_SELF：跑一次就把 adb 拉回 1，然后立刻给自己排下一次（自循环，30~60 秒一轮）
 *  - JOB_BOOT：开机后约 25 秒打一枪，专打厂商写 0 的那个时间窗
 *  - JOB_PERIODIC：15 分钟周期性兜底
 */
public class KeeperJobService extends JobService {
    private static final String TAG = "AdbKeeper";
    private static final int JOB_SELF = 4001;
    private static final int JOB_PERIODIC = 4002;
    private static final int JOB_BOOT = 4003;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "job fire id=" + params.getJobId());
        KeeperUtil.ensure(getApplicationContext());
        KeeperService.kick(getApplicationContext());
        scheduleSelf(getApplicationContext());
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static JobInfo.Builder base(Context ctx, int id) {
        JobInfo.Builder b = new JobInfo.Builder(id,
                new ComponentName(ctx, KeeperJobService.class));
        if (Build.VERSION.SDK_INT >= 21) b.setPersisted(true);
        return b;
    }

    private static boolean schedule(Context ctx, JobInfo info, String what) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            int r = js.schedule(info);
            Log.i(TAG, "schedule " + what + " -> " + (r == JobScheduler.RESULT_SUCCESS));
            return r == JobScheduler.RESULT_SUCCESS;
        } catch (Throwable t) {
            Log.w(TAG, "schedule " + what + " failed", t);
            return false;
        }
    }

    /** 排下一次「尽快执行」的持久化任务（自循环）。 */
    static void scheduleSelf(Context ctx) {
        try {
            JobInfo info = base(ctx, JOB_SELF)
                    .setMinimumLatency(8000L)     // 8 秒后再来一次
                    .setOverrideDeadline(20000L)  // 最迟 20 秒
                    .build();
            schedule(ctx, info, "self");
        } catch (Throwable t) {
            Log.w(TAG, "scheduleSelf failed", t);
        }
    }

    /** 开机那一枪：25 秒后执行一次，正对厂商写 0 的时间窗。 */
    static void scheduleBootShot(Context ctx) {
        try {
            JobInfo info = base(ctx, JOB_BOOT)
                    .setMinimumLatency(25000L)
                    .setOverrideDeadline(40000L)
                    .build();
            schedule(ctx, info, "bootShot");
        } catch (Throwable t) {
            Log.w(TAG, "scheduleBootShot failed", t);
        }
    }

    /** 周期性兜底：即使自循环被系统清掉，15 分钟一轮也能救回来。 */
    static void schedulePeriodic(Context ctx) {
        try {
            JobInfo info = base(ctx, JOB_PERIODIC)
                    .setPeriodic(15 * 60 * 1000L)
                    .build();
            schedule(ctx, info, "periodic");
        } catch (Throwable t) {
            Log.w(TAG, "schedulePeriodic failed", t);
        }
    }

    /** 把三条任务都架上（幂等，可重复调用）。 */
    static void arm(Context ctx) {
        scheduleSelf(ctx);
        scheduleBootShot(ctx);
        schedulePeriodic(ctx);
        dump(ctx);
    }

    /** 把当前已排的任务打进日志，方便 adb 侧核对到底有没有排上。 */
    static void dump(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            StringBuilder sb = new StringBuilder();
            for (JobInfo i : js.getAllPendingJobs()) {
                sb.append(i.getId()).append("@").append(i.getIntervalMillis())
                  .append("/").append(i.getMinLatencyMillis()).append("  ");
            }
            Log.i(TAG, "pending jobs: [" + sb.toString().trim() + "]");
        } catch (Throwable t) {
            Log.w(TAG, "dump jobs failed", t);
        }
    }
}
