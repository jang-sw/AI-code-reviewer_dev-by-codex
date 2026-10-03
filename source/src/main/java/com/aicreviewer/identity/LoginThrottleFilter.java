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
                response.setStatus(503);
                response.setHeader("Retry-After", "30");
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("지금은 로그인을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
                return;
            }
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("로그인 시도가 너무 많습니다. " + decision.retryAfterSeconds() + "초 후 다시 시도해 주세요.");
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
