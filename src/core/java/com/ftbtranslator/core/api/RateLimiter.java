package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;

import java.util.function.Function;

/**
 * Enforces a minimum interval between HTTP requests based on a requests-per-minute (RPM) rate.
 * <p>
 * In <b>manual</b> mode the rate is fixed. In <b>adaptive</b> (automatic) mode it follows the account:
 * <ul>
 *   <li>When the provider reports the account's request limit in a response header (Anthropic:
 *       {@value #ANTHROPIC_REQUESTS_LIMIT_HEADER}), the rate jumps to 90% of it.</li>
 *   <li>Otherwise it grows by ~20% after every {@value #SUCCESSES_TO_GROW} successful requests, up to a
 *       ceiling, so faster paid tiers are used without hard-coding them.</li>
 *   <li>Every HTTP 429 halves it, so free tiers settle just below their real limit.</li>
 * </ul>
 * A rate of {@code 0} or less means unlimited.
 */
public final class RateLimiter {

    public static final int MAX_RPM = 600;
    static final int SUCCESSES_TO_GROW = 10;
    static final String ANTHROPIC_REQUESTS_LIMIT_HEADER = "anthropic-ratelimit-requests-limit";

    private final boolean adaptive;
    private final int growthCeiling;
    private int requestsPerMinute;
    private long minIntervalMillis;
    private long lastRequestTimestampMillis;
    private int consecutiveSuccesses;
    private boolean accountLimitKnown;

    /** Fixed (manual) rate. */
    public RateLimiter(int requestsPerMinute) {
        this(requestsPerMinute, false, requestsPerMinute);
    }

    /**
     * @param startRpm      initial rate
     * @param adaptive      whether the rate adapts to 429s, successes and reported account limits
     * @param growthCeiling highest rate reached by growth alone (an account limit reported by the provider
     *                      overrides it, up to {@link #MAX_RPM})
     */
    public RateLimiter(int startRpm, boolean adaptive, int growthCeiling) {
        this.adaptive = adaptive;
        this.growthCeiling = Math.min(MAX_RPM, Math.max(startRpm, growthCeiling));
        setRate(startRpm);
    }

    public synchronized long minIntervalMillis() {
        return minIntervalMillis;
    }

    public synchronized int requestsPerMinute() {
        return requestsPerMinute;
    }

    public boolean isAdaptive() {
        return adaptive;
    }

    private void setRate(int rpm) {
        this.requestsPerMinute = rpm;
        this.minIntervalMillis = rpm <= 0 ? 0L : Math.max(100L, Math.round(60_000.0 / rpm));
    }

    /**
     * Records a successful response.
     *
     * @param header case-insensitive response header lookup (returns {@code null} when absent)
     * @return the new rate when it changed, or {@code -1}
     */
    public synchronized int onSuccess(Function<String, String> header) {
        if (!adaptive || requestsPerMinute <= 0) {
            return -1;
        }
        int accountLimit = parsePositiveInt(header == null ? null : header.apply(ANTHROPIC_REQUESTS_LIMIT_HEADER));
        if (accountLimit > 0) {
            accountLimitKnown = true;
            int target = Math.min(MAX_RPM, Math.max(1, (int) Math.floor(accountLimit * 0.9)));
            if (target != requestsPerMinute) {
                setRate(target);
                return target;
            }
            return -1;
        }
        if (accountLimitKnown) {
            return -1;
        }
        consecutiveSuccesses++;
        if (consecutiveSuccesses < SUCCESSES_TO_GROW || requestsPerMinute >= growthCeiling) {
            return -1;
        }
        consecutiveSuccesses = 0;
        int next = Math.min(growthCeiling, requestsPerMinute + Math.max(1, requestsPerMinute / 5));
        setRate(next);
        return next;
    }

    /**
     * Records an HTTP 429 (too many requests): halves the rate in adaptive mode.
     *
     * @return the new rate when it changed, or {@code -1}
     */
    public synchronized int onRateLimited() {
        consecutiveSuccesses = 0;
        if (!adaptive || requestsPerMinute <= 1) {
            return -1;
        }
        int next = Math.max(1, requestsPerMinute / 2);
        setRate(next);
        return next;
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

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
