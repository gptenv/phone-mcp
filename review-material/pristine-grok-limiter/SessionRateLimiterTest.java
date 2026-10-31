package dev.phonemcp.core;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class SessionRateLimiterTest {
    private static final long P = 1_000L; // refill period in fake nanos

    private static final class FakeClock {
        final AtomicLong now;
        FakeClock(long start) { now = new AtomicLong(start); }
        long get() { return now.get(); }
        void set(long v) { now.set(v); }
    }

    private static SessionRateLimiter limiter(int cap, FakeClock c) {
        return new SessionRateLimiter(cap, P, c::get);
    }

    private static void drain(SessionRateLimiter l, int n) {
        for (int i = 0; i < n; i++) assertTrue("drain " + i, l.tryAcquire(1).accepted());
    }

    @Test public void startsFullAndDeniesAfterBurst() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(3, c);
        drain(l, 3);
        SessionRateLimiter.Decision d = l.tryAcquire(1);
        assertFalse(d.accepted());
        assertEquals(P, d.retryAfterNanos());
        assertEquals(0, l.availableTokens());
    }

    @Test public void exactRefillBoundary() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(2, c);
        drain(l, 2);
        c.set(P - 1);
        SessionRateLimiter.Decision d = l.tryAcquire(1);
        assertFalse(d.accepted());
        assertEquals(1, d.retryAfterNanos());
        c.set(P);
        assertTrue(l.tryAcquire(1).accepted());
        assertFalse(l.tryAcquire(1).accepted());
    }

    @Test public void partialPeriodIsKeptAcrossRefills() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(5, c);
        drain(l, 5);
        c.set(P + P / 2); // 1.5 periods
        assertTrue(l.tryAcquire(1).accepted());
        assertFalse(l.tryAcquire(1).accepted());
        c.set(2 * P); // remaining half period completes second token
        assertTrue(l.tryAcquire(1).accepted());
    }

    @Test public void retryAfterAccountsForElapsedFraction() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(4, c);
        drain(l, 4);
        c.set(300);
        SessionRateLimiter.Decision d = l.tryAcquire(3);
        assertFalse(d.accepted());
        assertEquals(3 * P - 300, d.retryAfterNanos());
        c.set(3 * P);
        assertTrue(l.tryAcquire(3).accepted());
    }

    @Test public void capacityCapsBurstAfterLongIdle() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(3, c);
        drain(l, 3);
        c.set(1_000 * P);
        drain(l, 3);
        assertFalse(l.tryAcquire(1).accepted());
    }

    @Test public void idleWhileFullIsNotBanked() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(2, c);
        c.set(10 * P);
        assertTrue(l.tryAcquire(2).accepted());
        c.set(10 * P + P - 1);
        assertFalse(l.tryAcquire(1).accepted());
    }

    @Test public void multiTokenDenialIsAtomic() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(3, c);
        assertTrue(l.tryAcquire(2).accepted());
        SessionRateLimiter.Decision d = l.tryAcquire(2);
        assertFalse(d.accepted());
        assertEquals(1, l.availableTokens()); // nothing consumed
        assertTrue(l.tryAcquire(1).accepted());
    }

    @Test public void countOutsideRangeRejected() {
        SessionRateLimiter l = limiter(3, new FakeClock(0));
        for (int bad : new int[] {0, -1, 4, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            try { l.tryAcquire(bad); fail("count " + bad); } catch (IllegalArgumentException expected) { }
        }
        assertEquals(3, l.availableTokens());
    }

    @Test public void invalidConfigRejected() {
        FakeClock c = new FakeClock(0);
        try { new SessionRateLimiter(0, P, c::get); fail(); } catch (IllegalArgumentException e) { }
        try { new SessionRateLimiter(-1, P, c::get); fail(); } catch (IllegalArgumentException e) { }
        try { new SessionRateLimiter(1, 0, c::get); fail(); } catch (IllegalArgumentException e) { }
        try { new SessionRateLimiter(1, -5, c::get); fail(); } catch (IllegalArgumentException e) { }
        try { new SessionRateLimiter(2, Long.MAX_VALUE, c::get); fail(); } catch (IllegalArgumentException e) { }
        try { new SessionRateLimiter(1, P, null); fail(); } catch (NullPointerException e) { }
        new SessionRateLimiter(1, Long.MAX_VALUE, c::get); // 1 * MAX fits
    }

    @Test public void backwardsClockNeverRefillsOrRewindsPoint() {
        FakeClock c = new FakeClock(10 * P);
        SessionRateLimiter l = limiter(2, c);
        drain(l, 2);
        c.set(0); // jump back 10 periods
        SessionRateLimiter.Decision d = l.tryAcquire(1);
        assertFalse(d.accepted());
        assertEquals(11 * P, d.retryAfterNanos()); // catch-up 10 P plus one period
        assertEquals(0, l.availableTokens());
        c.set(10 * P + P - 1); // still short of one period after original point
        assertFalse(l.tryAcquire(1).accepted());
        c.set(11 * P);
        assertTrue(l.tryAcquire(1).accepted());
        assertFalse(l.tryAcquire(1).accepted());
    }

    @Test public void zeroAndNegativeAbsoluteReadingsAreFine() {
        FakeClock c = new FakeClock(-5 * P);
        SessionRateLimiter l = limiter(1, c);
        assertTrue(l.tryAcquire(1).accepted());
        c.set(-4 * P);
        assertTrue(l.tryAcquire(1).accepted());
        c.set(0);
        assertTrue(l.tryAcquire(1).accepted());
    }

    @Test public void largeJumpAndWrapAroundDoNotOverflow() {
        FakeClock c = new FakeClock(Long.MAX_VALUE - P / 2);
        SessionRateLimiter l = limiter(3, c);
        drain(l, 3);
        c.set(Long.MIN_VALUE + P); // wrapped: difference is 1.5 P forward
        assertTrue(l.tryAcquire(1).accepted());
        assertFalse(l.tryAcquire(1).accepted());

        FakeClock c2 = new FakeClock(0);
        SessionRateLimiter l2 = new SessionRateLimiter(Integer.MAX_VALUE, 1L, c2::get);
        assertTrue(l2.tryAcquire(Integer.MAX_VALUE).accepted());
        c2.set(Long.MAX_VALUE);
        assertTrue(l2.tryAcquire(Integer.MAX_VALUE).accepted()); // capped, no overflow
        SessionRateLimiter.Decision d = l2.tryAcquire(Integer.MAX_VALUE);
        assertFalse(d.accepted());
        assertEquals(Integer.MAX_VALUE, d.retryAfterNanos());
    }

    @Test public void retryAfterIsBoundedByCountTimesPeriod() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(5, c);
        drain(l, 5);
        for (int n = 1; n <= 5; n++) {
            long r = l.tryAcquire(n).retryAfterNanos();
            assertTrue(r > 0 && r <= n * P);
        }
    }

    @Test public void retryAfterHonoredExactlyAfterBackwardsJump() {
        FakeClock c = new FakeClock(5 * P);
        SessionRateLimiter l = limiter(3, c);
        drain(l, 3);
        c.set(2 * P); // 3 P behind high-water mark
        long r = l.tryAcquire(2).retryAfterNanos();
        assertEquals(3 * P + 2 * P, r);
        c.set(2 * P + r - 1);
        assertFalse(l.tryAcquire(2).accepted());
        c.set(2 * P + r);
        assertTrue(l.tryAcquire(2).accepted());
    }

    @Test public void deniedMultiTokenDuringBackwardsJumpKeepsTokens() {
        FakeClock c = new FakeClock(10 * P);
        SessionRateLimiter l = limiter(3, c);
        assertTrue(l.tryAcquire(2).accepted());
        c.set(0);
        assertFalse(l.tryAcquire(2).accepted());
        assertEquals(1, l.availableTokens());
        assertTrue(l.tryAcquire(1).accepted()); // remaining token still usable while behind
    }

    @Test public void retryAfterSaturatesOnHugeBackwardsJump() {
        long period = Long.MAX_VALUE / 2;
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = new SessionRateLimiter(2, period, c::get);
        assertTrue(l.tryAcquire(2).accepted());
        c.set(-(period + 10)); // catch-up plus one period exceeds Long.MAX_VALUE
        SessionRateLimiter.Decision d = l.tryAcquire(1);
        assertFalse(d.accepted());
        assertEquals(Long.MAX_VALUE, d.retryAfterNanos());
    }

    @Test public void retryAfterSaturatesAtMinValueDifference() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(1, c);
        assertTrue(l.tryAcquire(1).accepted());
        c.set(Long.MIN_VALUE); // difference is Long.MIN_VALUE; negation would overflow
        SessionRateLimiter.Decision d = l.tryAcquire(1);
        assertFalse(d.accepted());
        assertEquals(Long.MAX_VALUE, d.retryAfterNanos());
    }

    @Test public void countAboveCapacityIsCallerErrorAndTakesNothing() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter l = limiter(3, c);
        try { l.tryAcquire(4); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(3, l.availableTokens());
        assertTrue(l.tryAcquire(3).accepted());
    }

    @Test public void separateSessionsAreIsolated() {
        FakeClock c = new FakeClock(0);
        SessionRateLimiter oldSession = limiter(2, c);
        drain(oldSession, 2);
        SessionRateLimiter newSession = limiter(2, c); // created at session start
        drain(newSession, 2);
        assertFalse(oldSession.tryAcquire(1).accepted());
    }

    @Test public void concurrentCallersNeverExceedCapacity() throws Exception {
        FakeClock c = new FakeClock(0);
        final int cap = 50;
        final SessionRateLimiter l = new SessionRateLimiter(cap, P, c::get);
        final int threads = 16, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < perThread; i++) if (l.tryAcquire(1).accepted()) accepted.incrementAndGet();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(cap, accepted.get()); // clock frozen: exactly the burst
        assertEquals(0, l.availableTokens());
    }

    @Test public void defaultsAreSane() {
        SessionRateLimiter l = SessionRateLimiter.withDefaults(new FakeClock(0)::get);
        assertEquals(10, l.capacity());
        assertEquals(200_000_000L, l.refillPeriodNanos());
    }
}
