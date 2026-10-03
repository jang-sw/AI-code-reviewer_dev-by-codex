package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SignupThrottleFilterTest {
    private final MutableClock clock = new MutableClock();
    private final AttemptLimiterFixture database = new AttemptLimiterFixture(clock);
    @AfterEach void close() { database.close(); }
    @ParameterizedTest
    @ValueSource(strings = { "/signup", "/sign%75p", "/%73ignup", "/reviewer/sign%75p" })
    void encodedSignupPathsAndChangedUsernamesCannotBypassAddressQuota(String path) throws Exception {
        var filter = new SignupThrottleFilter(database.signup(1, 60, 100));
        var calls = new AtomicInteger();
        filter.doFilter(request("POST", "/signup", "first"), new MockHttpServletResponse(), (a, b) -> calls.incrementAndGet());
        var response = new MockHttpServletResponse();
        var second = request("POST", path, "different");
        second.addHeader("X-Forwarded-For", "1.2.3.4");
        filter.doFilter(second, response, (a, b) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain("first", "different", "1.2.3.4");
    }

    @Test
    void unavailableSharedStoreBlocksSignupWithFixedRetryableResponse() throws Exception {
        var filter = new SignupThrottleFilter(database.signup(1, 60, 100));
        database.jdbc.execute("ALTER TABLE auth_attempt_bucket RENAME TO unavailable_fixture_bucket");
        var calls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/sign%75p", "synthetic-private-applicant"), response, (a, b) -> calls.incrementAndGet());
        assertThat(calls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("잠시 후").doesNotContain("synthetic-private", "auth_attempt", "SELECT", "127.0.0.1");
    }

    @Test
    void signupGetAndLoginPostDoNotConsumeSignupQuota() throws Exception {
        var filter = new SignupThrottleFilter(database.signup(1, 60, 100));
        var calls = new AtomicInteger();
        for (var request : new MockHttpServletRequest[] {
                request("GET", "/signup", "first"), request("POST", "/login", "first"), request("POST", "/signup", "first") }) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (a, b) -> calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(calls).hasValue(3);
    }

    @Test
    void boundedBucketsFailClosedAndExpireWithoutExtendingOnRetries() {
        var limiter = database.signup(1, 60, 2);
        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
        for (int i = 2; i < 100; i++) assertThat(limiter.acquire("10.0.0." + i).allowed()).isFalse();
        clock.millis += 50_000;
        assertThat(limiter.acquire("10.0.0.1").retryAfterSeconds()).isEqualTo(10);
        clock.millis += 10_000;
        assertThat(limiter.acquire("10.0.0.2").allowed()).isTrue();
    }

    private static MockHttpServletRequest request(String method, String path, String username) {
        var request = new MockHttpServletRequest(method, path);
        if (path.startsWith("/reviewer/")) request.setContextPath("/reviewer");
        request.setServletPath(path.endsWith("login") ? "/login" : "/signup");
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("username", username);
        return request;
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
