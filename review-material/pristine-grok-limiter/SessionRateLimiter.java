package dev.phonemcp.core;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-session token bucket for authenticated tools/call admission.
 *
 * <p>Integer-only: one token is added every {@code refillPeriodNanos}, up to {@code capacity}.
 * The bucket starts full. It stores no bearer token or session identity; the caller authenticates
 * first, creates one instance per session, and discards it when the session ends, so a new
 * session never inherits the old balance. There is deliberately no reset method.
 *
 * <p>Clock: an injected monotonic nanosecond source (e.g. {@code System::nanoTime} or
 * {@code SystemClock::elapsedRealtimeNanos}). Only differences are used, so any absolute value,
 * including 0 or negative, is valid. A reading earlier than the last refill point (backwards
 * jump) never refills and never moves the refill point back (a high-water mark), so it cannot
 * mint tokens. A denial during a backwards jump reports a retry-after that includes the
 * catch-up interval back to the high-water mark. If that sum exceeds Long.MAX_VALUE it
 * saturates at Long.MAX_VALUE. A forward gap of 2^63 ns or more is indistinguishable from a
 * backwards jump under wrap-safe subtraction and is treated as one (about 292 years).
 *
 * <p>A denial is decided before anything executes and consumes nothing; callers report it as a
 * retryable pre-execution error, never as an uncertain action.
 */
public final class SessionRateLimiter {

    /** Default burst: 10 calls. */
    public static final int DEFAULT_CAPACITY = 10;
    /** Default sustained rate: one call per 200 ms (5/s). */
    public static final long DEFAULT_REFILL_PERIOD_NANOS = 200_000_000L;

    /** Result of one admission attempt. */
    public static final class Decision {
        private static final Decision ACCEPTED = new Decision(true, 0L);
        private final boolean accepted;
        private final long retryAfterNanos;

        private Decision(boolean accepted, long retryAfterNanos) {
            this.accepted = accepted;
            this.retryAfterNanos = retryAfterNanos;
        }

        public boolean accepted() { return accepted; }

        /**
         * 0 when accepted. Otherwise positive: at most count times refillPeriodNanos when the
         * clock is not behind the high-water mark, plus the catch-up interval when it is,
         * saturating at Long.MAX_VALUE. Exact for this limiter alone, assuming no other caller
         * takes tokens in the meantime.
         */
        public long retryAfterNanos() { return retryAfterNanos; }

        @Override public String toString() {
            return accepted ? "Decision[accepted]" : "Decision[denied, retryAfterNanos=" + retryAfterNanos + "]";
        }
    }

    private final int capacity;
    private final long refillPeriodNanos;
    private final LongSupplier clock;

    private long tokens;          // 0..capacity
    private long refillPointNanos; // clock value up to which elapsed time has been credited

    /**
     * @param clock must be a monotonic nanosecond source. This class does not make it monotonic.
     *     Production callers should pass SystemClock::elapsedRealtimeNanos (keeps counting in deep
     *     sleep) or System::nanoTime; never a wall clock such as System.currentTimeMillis.
     */
    public SessionRateLimiter(int capacity, long refillPeriodNanos, LongSupplier clock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (refillPeriodNanos <= 0) throw new IllegalArgumentException("refillPeriodNanos must be positive");
        try {
            Math.multiplyExact((long) capacity, refillPeriodNanos); // bounds every retry-after value
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("capacity * refillPeriodNanos overflows long", e);
        }
        this.capacity = capacity;
        this.refillPeriodNanos = refillPeriodNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokens = capacity;
        this.refillPointNanos = clock.getAsLong();
    }

    public static SessionRateLimiter withDefaults(LongSupplier clock) {
        return new SessionRateLimiter(DEFAULT_CAPACITY, DEFAULT_REFILL_PERIOD_NANOS, clock);
    }

    /**
     * Atomically takes {@code count} tokens or none.
     *
     * <p>A denied request takes nothing: the available token count is unchanged, apart from any
     * refill credited for elapsed time.
     *
     * @throws IllegalArgumentException if count is not in [1, capacity]. A count above capacity
     *     can never be admitted, so it is a caller error rather than a retryable denial; the
     *     caller must validate batch sizes before calling.
     */
    public synchronized Decision tryAcquire(int count) {
        if (count <= 0 || count > capacity) {
            throw new IllegalArgumentException("count must be in [1, " + capacity + "]");
        }
        long now = clock.getAsLong();
        long elapsed = now - refillPointNanos; // wrap-safe difference of monotonic readings
        long catchUp = 0;
        if (elapsed < 0) {
            // backwards: no refill, keep the high-water refill point
            catchUp = (elapsed == Long.MIN_VALUE) ? Long.MAX_VALUE : -elapsed;
            elapsed = 0;
        } else if (tokens >= capacity) {
            refillPointNanos = now; // full: idle time is not banked
            elapsed = 0;
        } else {
            long periods = elapsed / refillPeriodNanos;
            if (periods > 0) {
                long room = capacity - tokens;
                if (periods >= room) {
                    tokens = capacity;
                    refillPointNanos = now;
                    elapsed = 0;
                } else {
                    tokens += periods;
                    long credited = periods * refillPeriodNanos; // <= elapsed, no overflow
                    refillPointNanos += credited;
                    elapsed -= credited; // now in [0, refillPeriodNanos)
                }
            }
        }
        if (tokens >= count) {
            tokens -= count;
            return Decision.ACCEPTED;
        }
        long missing = count - tokens; // 1..capacity
        long wait = Math.multiplyExact(missing, refillPeriodNanos) - elapsed; // fits: checked in constructor
        long total;
        try {
            total = Math.addExact(catchUp, wait);
        } catch (ArithmeticException e) {
            total = Long.MAX_VALUE; // saturate
        }
        return new Decision(false, total);
    }

    /** Test/diagnostic view; not used for admission. */
    public synchronized long availableTokens() { return tokens; }

    public int capacity() { return capacity; }

    public long refillPeriodNanos() { return refillPeriodNanos; }
}
