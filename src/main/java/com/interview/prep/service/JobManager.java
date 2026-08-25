package com.interview.prep.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.interview.prep.dao.GenDao;
import com.interview.prep.dao.GenDao.JobSnapshot;

/**
 * 单线程后台批任务管理器：同一时刻最多一个任务，支持进度上报与停止。
 * 完成状态持久化到 job_state 表，重启后仍可查看上次结果。
 */
@Service
public class JobManager {

    private static final Logger log = LoggerFactory.getLogger(JobManager.class);

    public record Snapshot(String type, String status, int total, int done, int failed,
                           String message, boolean running) {}

    public interface BatchTask {
        void run(Control control) throws Exception;
    }

    public class Control {
        public boolean stopped() {
            return stopRequested.get();
        }

        public void progress(int done, int failed, String message) {
            update(new Snapshot(current.type(), current.status(), current.total(), done, failed,
                    message, true));
        }
    }

    private final GenDao genDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-batch");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean runningFlag = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Snapshot current;

    public JobManager(GenDao genDao) {
        this.genDao = genDao;
        var last = genDao.loadJob();
        if (last != null && !"RUNNING".equals(last.status())) {
            current = new Snapshot(last.type(), last.status(), last.total(), last.done(),
                    last.failed(), last.message(), false);
        }
    }

    public synchronized boolean tryStart(String type, int total, BatchTask task) {
        if (!runningFlag.compareAndSet(false, true)) {
            return false;
        }
        stopRequested.set(false);
        update(new Snapshot(type, "RUNNING", total, 0, 0, null, true));

        executor.submit(() -> {
            Control control = new Control();
            try {
                task.run(control);
                String status = stopRequested.get() ? "STOPPED" : "DONE";
                finish(status);
            } catch (Throwable t) {
                log.error("批任务异常终止", t);
                update(new Snapshot(current.type(), "FAILED", current.total(), current.done(),
                        current.failed(), abbrev(t.toString()), false));
            } finally {
                runningFlag.set(false);
            }
        });
        return true;
    }

    private void finish(String status) {
        update(new Snapshot(current.type(), status, current.total(), current.done(),
                current.failed(), current.message(), false));
    }

    private synchronized void update(Snapshot s) {
        current = s;
        try {
            genDao.saveJob(new JobSnapshot(s.type(), s.status(), s.total(), s.done(),
                    s.failed(), s.message()));
        } catch (Exception e) {
            log.warn("任务状态持久化失败", e);
        }
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public Snapshot snapshot() {
        return current;
    }

    public boolean busy() {
        return runningFlag.get();
    }

    private static String abbrev(String s) {
        return s == null ? null : (s.length() > 200 ? s.substring(0, 200) : s);
    }
}
