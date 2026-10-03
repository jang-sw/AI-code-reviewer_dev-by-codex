package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final AttemptLimiterFixture database = new AttemptLimiterFixture(clock);
    @AfterEach void close() { database.close(); }

    @Test
    void failedSuccessResetRetainsConsumedQuotaWithoutTurningAuthenticationIntoFailure() {
        var limiter = database.login(1, 100, 60, 100);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
        database.jdbc.execute("ALTER TABLE auth_attempt_bucket RENAME TO unavailable_fixture_bucket");
        limiter.succeeded("alice");
        database.jdbc.execute("ALTER TABLE unavailable_fixture_bucket RENAME TO auth_attempt_bucket");
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isFalse();
        assertThat(database.entries("LOGIN")).isEqualTo(2);
    }

    @Test
    void normalizedAccountCannotBypassLimitByChangingAddresses() {
        LoginAttemptLimiter limiter = database.login(2, 100, 60, 100);
        assertThat(limiter.acquire(" Alice ", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("ALICE", "10.0.0.2").allowed()).isTrue();
        assertThat(limiter.acquire("alice", "10.0.0.3").allowed()).isFalse();
        clock.advanceSeconds(60);
        assertThat(limiter.acquire("alice", "10.0.0.3").allowed()).isTrue();
    }

    @Test
    void addressCannotBypassLimitUsingDifferentAccountsOrSuccessfulLogins() {
        LoginAttemptLimiter limiter = database.login(10, 2, 60, 100);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
        limiter.succeeded("alice");
        assertThat(limiter.acquire("bob", "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("charlie", "10.0.0.1").allowed()).isFalse();
        assertThat(limiter.acquire("charlie", "10.0.0.2").allowed()).isTrue();
    }

    @Test
    void sharedRowsAreBoundedAndNewKeysDoNotEvictBlockedAccounts() {
        LoginAttemptLimiter limiter = database.login(1, 100, 60, 2);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire("random" + i, "10.0.0.2").allowed()).isFalse();
        }
        assertThat(database.entries("LOGIN")).isEqualTo(2);
        assertThat(limiter.acquire("alice", "10.0.0.1").allowed()).isFalse();
        clock.advanceSeconds(60);
        assertThat(limiter.acquire("bob", "10.0.0.2").allowed()).isTrue();
        assertThat(database.entries("LOGIN")).isEqualTo(2);
    }

    @Test
    void throttledRetriesDoNotExtendTheFixedWindow() {
        LoginAttemptLimiter limiter = database.login(1, 100, 60, 100);
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
