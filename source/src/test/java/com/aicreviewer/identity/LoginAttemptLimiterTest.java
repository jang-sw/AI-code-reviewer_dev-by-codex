package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void normalizedAccountCannotBypassLimitByChangingAddresses() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 100, 60, 100, clock);
        assertThat(limiter.acquire(" Alice ", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("ALICE", "10.0.0.2").allowed()).isTrue();
        assertThat(limiter.acquire("alice", "10.0.0.3").allowed()).isFalse();
        clock.advanceSeconds(60);
        assertThat(limiter.acquire("alice", "10.0.0.3").allowed()).isTrue();
    }

    @Test
    void addressCannotBypassLimitUsingDifferentAccountsOrSuccessfulLogins() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(10, 2, 60, 100, clock);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
        limiter.succeeded("alice");
        assertThat(limiter.acquire("bob", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("charlie", "10.0.0.1").allowed()).isFalse();
        assertThat(limiter.acquire("charlie", "10.0.0.2").allowed()).isTrue();
    }

    @Test
    void memoryIsBoundedAndNewKeysDoNotEvictBlockedAccounts() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 100, 60, 2, clock);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire("random" + i, "10.0.0.2").allowed()).isFalse();
        }
        assertThat(limiter.entryCount()).isEqualTo(2);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isFalse();
        clock.advanceSeconds(60);
        assertThat(limiter.acquire("bob", "10.0.0.2").allowed()).isTrue();
        assertThat(limiter.entryCount()).isEqualTo(2);
    }

    @Test
    void throttledRetriesDoNotExtendTheFixedWindow() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 100, 60, 100, clock);
        limiter.acquire("alice", "10.0.0.1");
        clock.advanceSeconds(50);
        assertThat(limiter.acquire("alice", "10.0.0.1").retryAfterSeconds()).isEqualTo(10);
        clock.advanceSeconds(10);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000;
        void advanceSeconds(long seconds) { millis += seconds * 1000; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
