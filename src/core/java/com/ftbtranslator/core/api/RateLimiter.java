package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;

/**
 * Enforces a minimum interval between HTTP requests based on {@code requestsPerMinute} (default 15 RPM
 * for Gemini free tier = 4000 ms spacing).
 */
public final class RateLimiter {

    private final long minIntervalMillis;
    private long lastRequestTimestampMillis;

    public RateLimiter(int requestsPerMinute) {
        if (requestsPerMinute <= 0) {
            this.minIntervalMillis = 0L;
        } else {
            this.minIntervalMillis = Math.max(100L, Math.round(60_000.0 / requestsPerMinute));
        }
    }

    public long minIntervalMillis() {
        return minIntervalMillis;
    }

    public synchronized void acquire(CancellationToken token) {
        token.checkpoint();
        if (minIntervalMillis <= 0L) {
            lastRequestTimestampMillis = System.currentTimeMillis();
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTimestampMillis;
        if (lastRequestTimestampMillis > 0L && elapsed < minIntervalMillis) {
            long waitMillis = minIntervalMillis - elapsed;
            token.sleep(waitMillis);
        }
        lastRequestTimestampMillis = System.currentTimeMillis();
    }
}
