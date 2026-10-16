package com.aicreviewer.identity;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

final class SignupThrottleFilter extends OncePerRequestFilter {
    private static final RequestMatcher SIGNUP = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/signup");
    private final SignupAttemptLimiter limiter;

    SignupThrottleFilter(SignupAttemptLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SIGNUP.matches(request)) {
            LoginAttemptLimiter.Decision decision;
            try {
                decision = limiter.acquire(request.getRemoteAddr());
            } catch (AttemptStoreUnavailableException unavailable) {
                AuthThrottleResponse.unavailable(request, response, AuthThrottleResponse.Form.SIGNUP);
                return;
            }
            if (!decision.allowed()) {
                AuthThrottleResponse.limited(request, response, AuthThrottleResponse.Form.SIGNUP, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
