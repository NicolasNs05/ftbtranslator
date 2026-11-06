package com.ftbtranslator.core;

/**
 * Cooperative pause / cancel flag shared between the UI thread and the worker thread.
 */
public final class CancellationToken {

    private static final long POLL_MILLIS = 100L;

    private volatile boolean cancelled;
    private volatile boolean paused;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Throws if cancelled; blocks while paused. Call between units of work. */
    public void checkpoint() {
        while (paused && !cancelled) {
            sleepQuietly(POLL_MILLIS);
        }
        if (cancelled) {
            throw new TranslationCancelledException();
        }
    }

    /** Sleeps in small slices so a cancel request is honoured quickly. */
    public void sleep(long millis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        while (true) {
            if (cancelled) {
                throw new TranslationCancelledException();
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            sleepQuietly(Math.min(POLL_MILLIS, remaining));
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }
    }
}
