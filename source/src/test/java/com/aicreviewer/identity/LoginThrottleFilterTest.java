package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginThrottleFilterTest {
    @ParameterizedTest
    @ValueSource(strings = { "/login", "/log%69n", "/%6Cogin", "/reviewer/log%69n" })
    void equivalentDecodedLoginPathsCannotBypassTheAccountLimit(String path) throws Exception {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 100, 60, 100, Clock.systemUTC());
        LoginThrottleFilter filter = new LoginThrottleFilter(limiter);
        AtomicInteger authenticationCalls = new AtomicInteger();
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(request("/login", "alice"), firstResponse,
                (request, response) -> authenticationCalls.incrementAndGet());
        var blockedResponse = new MockHttpServletResponse();
        filter.doFilter(request(path, "alice"), blockedResponse,
                (request, response) -> authenticationCalls.incrementAndGet());
        assertThat(authenticationCalls).hasValue(1);
        assertThat(blockedResponse.getStatus()).isEqualTo(429);
        assertThat(blockedResponse.getHeader("Retry-After")).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = { "alice\0", "\0alice", "alice\u0001", "\u001falice", "\talice\n" })
    void controlCharacterAliasesCannotReachPasswordAuthentication(String username) throws Exception {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(10, 100, 60, 100, Clock.systemUTC());
        LoginThrottleFilter filter = new LoginThrottleFilter(limiter);
        AtomicInteger authenticationCalls = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/log%69n", username), response,
                (request, result) -> authenticationCalls.incrementAndGet());
        assertThat(authenticationCalls).hasValue(0);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void changingAsciiControlEdgesDoesNotCreateSeparateAccountBuckets() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 100, 60, 100, Clock.systemUTC());
        assertThat(limiter.acquire("alice\0", "address-one").allowed()).isTrue();
        assertThat(limiter.acquire("\u0001alice", "address-two").allowed()).isTrue();
        assertThat(limiter.acquire("alice", "address-three").allowed()).isFalse();
    }

    private static MockHttpServletRequest request(String path, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (path.startsWith("/reviewer/")) request.setContextPath("/reviewer");
        request.setServletPath("/login");
        request.setParameter("username", username);
        request.setParameter("password", "Test-password-1234!");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
