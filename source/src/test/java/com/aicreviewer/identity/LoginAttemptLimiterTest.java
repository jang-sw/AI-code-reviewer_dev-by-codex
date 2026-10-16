package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT", "ADDRESS", "CAPACITY"})
    void fixtureClockMovesBackwardWithoutChangingStoredWindowOrExtendingRetryHint(String deniedBy) {
        boolean accountLimited = deniedBy.equals("ACCOUNT");
        boolean addressLimited = deniedBy.equals("ADDRESS");
        boolean capacityLimited = deniedBy.equals("CAPACITY");
        LoginAttemptLimiter limiter = database.login(accountLimited ? 5 : 100,
                addressLimited ? 5 : 100, 900, capacityLimited ? 2 : 100);
        Instant firstAttempt = clock.instant();
        int initialAllowed = capacityLimited ? 1 : 5;
        for (int i = 0; i < initialAllowed; i++) {
            assertThat(limiter.acquire(accountLimited ? "shared-account" : "account-" + i,
                    addressLimited ? "shared-address" : "address-" + i).allowed()).isTrue();
        }
        var buckets = database.jdbc.queryForList("SELECT * FROM auth_attempt_bucket ORDER BY scope,key_hash");
        var policies = database.jdbc.queryForList("SELECT * FROM auth_attempt_policy ORDER BY scope");
        assertThat(buckets).hasSize(capacityLimited ? 2 : 6);
        assertThat(database.jdbc.queryForObject("SELECT SUM(attempt_count) FROM auth_attempt_bucket", Integer.class))
                .isEqualTo(initialAllowed * 2);
        Instant firstExpiry = database.jdbc.queryForObject("SELECT MIN(expires_at) FROM auth_attempt_bucket",
                (result, row) -> result.getTimestamp(1).toInstant());
        assertThat(firstExpiry).isEqualTo(firstAttempt.plusSeconds(900));

        String deniedAccount = accountLimited ? "shared-account" : "new-account";
        String deniedAddress = addressLimited ? "shared-address" : "new-address";
        // Only the fixture supplier moves: production DB and operating-system clocks are untouched.
        for (long[] step : new long[][] {{-2, 900}, {2, 900}, {899, 1}}) {
            clock.advanceSeconds(step[0]);
            var denied = limiter.acquire(deniedAccount, deniedAddress);
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.retryAfterSeconds()).isEqualTo(step[1]);
            assertThat(database.jdbc.queryForList("SELECT * FROM auth_attempt_bucket ORDER BY scope,key_hash")).isEqualTo(buckets);
            assertThat(database.jdbc.queryForList("SELECT * FROM auth_attempt_policy ORDER BY scope")).isEqualTo(policies);
        }

        clock.advanceSeconds(1);
        var admitted = limiter.acquire(deniedAccount, deniedAddress);
        assertThat(clock.instant()).isEqualTo(firstAttempt.plusSeconds(900));
        assertThat(admitted.allowed()).isTrue();
        assertThat(admitted.retryAfterSeconds()).isZero();
        assertThat(database.entries("LOGIN")).isEqualTo(2);
        assertThat(database.jdbc.queryForObject("SELECT SUM(attempt_count) FROM auth_attempt_bucket", Integer.class)).isEqualTo(2);
        Instant nextEarliestExpiry = database.jdbc.queryForObject("SELECT MIN(expires_at) FROM auth_attempt_bucket",
                (result, row) -> result.getTimestamp(1).toInstant());
        Instant nextLatestExpiry = database.jdbc.queryForObject("SELECT MAX(expires_at) FROM auth_attempt_bucket",
                (result, row) -> result.getTimestamp(1).toInstant());
        assertThat(nextEarliestExpiry).isEqualTo(firstAttempt.plusSeconds(1800));
        assertThat(nextLatestExpiry).isEqualTo(firstAttempt.plusSeconds(1800));
        assertThat(database.jdbc.queryForList("SELECT * FROM auth_attempt_policy ORDER BY scope")).isEqualTo(policies);
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
