package com.aicreviewer.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginThrottleFilter extends OncePerRequestFilter {
    private static final RequestMatcher LOGIN_REQUEST = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/login");
    private final LoginAttemptLimiter limiter;

    LoginThrottleFilter(LoginAttemptLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (LOGIN_REQUEST.matches(request)) {
            String username = request.getParameter("username");
            LoginAttemptLimiter.Decision decision;
            try {
                decision = limiter.acquire(username, request.getRemoteAddr());
            } catch (AttemptStoreUnavailableException unavailable) {
                AuthThrottleResponse.unavailable(request, response, AuthThrottleResponse.Form.LOGIN);
                return;
            }
            if (!decision.allowed()) {
                AuthThrottleResponse.limited(request, response, AuthThrottleResponse.Form.LOGIN, decision.retryAfterSeconds());
                return;
            }
            String password = request.getParameter("password");
            if (username == null || username.strip().length() > 80 || username.chars().anyMatch(Character::isISOControl)
                    || password == null
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                response.sendRedirect(request.getContextPath() + "/login?error");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
