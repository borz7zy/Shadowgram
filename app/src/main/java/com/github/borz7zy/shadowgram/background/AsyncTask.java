/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.background;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AsyncTask<Params, Progress, Result> {
    public enum State { PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }
    public enum Priority { BACKGROUND, NORMAL, DISPLAY }
    private static final String TAG = "AsyncTask";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CPU = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static ThreadFactory factory(final String name) {
        final AtomicInteger ids = new AtomicInteger();
        return r -> new Thread(r, "AsyncTask-" + name + "-" + ids.incrementAndGet());
    }
    private static ThreadPoolExecutor pool(String name, int threads, int capacity) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(capacity), factory(name), new ThreadPoolExecutor.AbortPolicy());
        p.allowCoreThreadTimeOut(true);
        return p;
    }
    public static final Executor CPU_EXECUTOR = pool("cpu", Math.max(1, Math.min(4, CPU - 1)), 128);
    public static final Executor IO_EXECUTOR = pool("io", Math.max(2, Math.min(8, CPU)), 128);
    public static final Executor MEDIA_EXECUTOR = pool("media", Math.max(1, Math.min(2, CPU / 2)), 64);
    public static final Executor SERIAL_EXECUTOR = pool("serial", 1, 128);
    public static final Executor sPool = CPU_EXECUTOR;
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, factory("timer"));

    /** Close from onDestroyView/onDestroy; suppresses subsequent callbacks. */
    public static final class Scope implements AutoCloseable {
        private final Set<AsyncTask<?, ?, ?>> tasks = new HashSet<>();
        private volatile boolean closed;
        private synchronized boolean add(AsyncTask<?, ?, ?> task) {
            return !closed && tasks.add(task);
        }
        private synchronized void remove(AsyncTask<?, ?, ?> task) { tasks.remove(task); }
        public boolean isClosed() { return closed; }
        @Override public void close() {
            AsyncTask<?, ?, ?>[] snapshot;
            synchronized (this) {
                if (closed) return;
                closed = true;
                snapshot = tasks.toArray(new AsyncTask<?, ?, ?>[0]);
                tasks.clear();
            }
            for (AsyncTask<?, ?, ?> task : snapshot) task.cancel();
        }
    }

    private final Object lock = new Object();
    private volatile State state = State.PENDING;
    private Scope scope;
    private Priority priority = Priority.BACKGROUND;
    private long timeoutMs, progressIntervalMs = 33;
    private volatile long submittedAt = -1, startedAt = -1, finishedAt = -1;
    private FutureTask<Void> work;
    private Executor executor;
    private ScheduledFuture<?> timeout;
    private boolean outcomePosted, progressPosted;
    private Progress latestProgress;
    private final Runnable progressDelivery = this::deliverProgress;

    protected abstract Result doInBackground(Params... params) throws Throwable;
    protected void onPreExecute() {}
    protected void onProgressUpdate(Progress progress) {}
    protected void onPostExecute(Result result) {}
    protected void onError(Throwable t) { Log.e(TAG, "Task failed", t); }
    protected void onCanceled(Result result) {}
    protected void onTimeout(TimeoutException t) { onError(t); }

    public final State getState() { return state; }
    public final boolean isCanceled() { return state == State.CANCELLED || state == State.TIMED_OUT; }
    public final boolean isCancelled() { return isCanceled(); }
    public final boolean isDone() { return terminal(state); }
    public final long getQueueTimeMillis() { return startedAt < 0 ? -1 : startedAt - submittedAt; }
    /** Time until terminal decision, not necessarily actual worker exit after cancellation. */
    public final long getElapsedTimeMillis() { return finishedAt < 0 || submittedAt < 0 ? -1 : finishedAt - submittedAt; }
    private static boolean terminal(State s) {
        return s == State.COMPLETED || s == State.FAILED || s == State.CANCELLED || s == State.TIMED_OUT;
    }
    private void pending() { if (state != State.PENDING) throw new IllegalStateException("Task is one-shot"); }
    public final AsyncTask<Params, Progress, Result> inScope(Scope value) {
        synchronized (lock) { pending(); scope = value; } return this;
    }
    public final AsyncTask<Params, Progress, Result> withPriority(Priority value) {
        if (value == null) throw new NullPointerException("priority");
        synchronized (lock) { pending(); priority = value; } return this;
    }
    public final AsyncTask<Params, Progress, Result> withTimeout(long millis) {
        if (millis < 0) throw new IllegalArgumentException("timeout");
        synchronized (lock) { pending(); timeoutMs = millis; } return this;
    }
    public final AsyncTask<Params, Progress, Result> withProgressInterval(long millis) {
        if (millis < 16) throw new IllegalArgumentException("minimum 16 ms");
        synchronized (lock) { pending(); progressIntervalMs = millis; } return this;
    }
    @SafeVarargs public final void execPool(Params... params) { execOn(CPU_EXECUTOR, params); }
    @SafeVarargs public final void execOn(Executor target, Params... params) {
        if (target == null) throw new NullPointerException("executor");
        if (!begin(target, params)) return;
        MAIN.post(() -> {
            if (!prepare()) return;
            FutureTask<Void> job;
            synchronized (lock) { if (terminal(state)) return; job = work; }
            try {
                target.execute(job);
                // Handles cancellation between obtaining the job and submitting it.
                if (job.isCancelled() && target instanceof ThreadPoolExecutor)
                    ((ThreadPoolExecutor) target).remove(job);
            } catch (RuntimeException e) { postOutcome(null, e); }
        });
    }
    /** Synchronous worker-only API. Never call from main or while main waits for this thread. */
    @SafeVarargs public final void execInCurrThread(Params... params) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Use execPool on the main thread");
        if (!begin(null, params)) return;
        CountDownLatch ready = new CountDownLatch(1);
        MAIN.post(() -> { try { prepare(); } finally { ready.countDown(); } });
        try { ready.await(); }
        catch (InterruptedException e) { cancel(); Thread.currentThread().interrupt(); return; }
        FutureTask<Void> job;
        synchronized (lock) { job = terminal(state) ? null : work; }
        if (job != null) job.run();
    }
    private boolean begin(Executor target, Params[] params) {
        final Params[] input = params == null ? null : params.clone();
        synchronized (lock) {
            pending();
            state = State.QUEUED;
            submittedAt = SystemClock.elapsedRealtime();
            executor = target;
            work = new FutureTask<>(() -> { runWorker(input); return null; });
            if (scope != null && !scope.add(this)) {
                stopLocked(State.CANCELLED, true); return false;
            }
            if (timeoutMs > 0) timeout = TIMER.schedule(() -> stop(State.TIMED_OUT, true), timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        }
    }
    private boolean prepare() {
        if (isDone()) return false;
        if (scope != null && scope.isClosed()) { cancel(); return false; }
        try { onPreExecute(); }
        catch (Exception e) { postOutcome(null, e); return false; }
        catch (Error e) { postOutcome(null, e); throw e; }
        return !isDone();
    }
    private void runWorker(Params[] input) {
        synchronized (lock) {
            if (state != State.QUEUED) return;
            state = State.RUNNING;
            startedAt = SystemClock.elapsedRealtime();
        }
        // A custom executor must not run heavy tasks on the UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            postOutcome(null, new IllegalStateException("Executor ran on main thread")); return;
        }
        int old = Process.getThreadPriority(Process.myTid());
        try {
            int nice = priority == Priority.DISPLAY ? Process.THREAD_PRIORITY_DISPLAY :
                    priority == Priority.NORMAL ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND;
            try { Process.setThreadPriority(nice); } catch (SecurityException e) { Log.w(TAG, "Priority unavailable", e); }
            if (!isDone()) postOutcome(doInBackground(input), null);
        } catch (Throwable t) {
            postOutcome(null, t);
            if (t instanceof Error) Log.e(TAG, "Fatal worker error", t);
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            try { Process.setThreadPriority(old); } catch (SecurityException e) { Log.w(TAG, "Priority restore failed", e); }
        }
    }
    public final void publishProgress(Progress progress) {
        synchronized (lock) {
            if (state != State.RUNNING || outcomePosted) return;
            latestProgress = progress;
            if (!progressPosted) {
                progressPosted = true;
                MAIN.postDelayed(progressDelivery, progressIntervalMs);
            }
        }
    }
    private void deliverProgress() {
        Progress value;
        synchronized (lock) {
            if (!progressPosted || state != State.RUNNING || outcomePosted) return;
            value = latestProgress; latestProgress = null; progressPosted = false;
        }
        if (!isDone() && callbacksAllowed()) onProgressUpdate(value);
    }
    private boolean callbacksAllowed() { return scope == null || !scope.isClosed(); }
    private void postOutcome(Result result, Throwable error) {
        synchronized (lock) {
            if (terminal(state) || outcomePosted) return;
            outcomePosted = true;
            MAIN.post(() -> deliverOutcome(result, error));
        }
    }
    private void deliverOutcome(Result result, Throwable error) {
        synchronized (lock) {
            if (terminal(state)) return;
            state = error == null ? State.COMPLETED : State.FAILED;
            cleanupLocked();
        }
        if (!callbacksAllowed()) return;
        if (error == null) onPostExecute(result); else onError(error);
    }
    public final void cancel() { cancel(true); }
    public final boolean cancel(boolean interrupt) { return stop(State.CANCELLED, interrupt); }
    private boolean stop(State reason, boolean interrupt) {
        synchronized (lock) {
            if (terminal(state)) return false;
            stopLocked(reason, interrupt); return true;
        }
    }
    private void stopLocked(State reason, boolean interrupt) {
        state = reason;
        if (work != null) {
            work.cancel(interrupt);
            if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(work);
        }
        cleanupLocked();
        MAIN.post(() -> {
            if (!callbacksAllowed()) return;
            if (reason == State.TIMED_OUT) onTimeout(new TimeoutException("Task exceeded " + timeoutMs + " ms"));
            else onCanceled(null);
        });
    }
    private void cleanupLocked() {
        finishedAt = SystemClock.elapsedRealtime();
        if (timeout != null) { timeout.cancel(false); TIMER.remove((Runnable) timeout); timeout = null; }
        MAIN.removeCallbacks(progressDelivery);
        latestProgress = null; progressPosted = false;
        work = null; executor = null;
        if (scope != null) scope.remove(this);
    }
}
