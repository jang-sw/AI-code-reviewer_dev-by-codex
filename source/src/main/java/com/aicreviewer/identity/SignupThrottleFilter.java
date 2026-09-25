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
            var decision = limiter.acquire(request.getRemoteAddr());
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("가입 요청이 너무 많습니다. " + decision.retryAfterSeconds() + "초 후 다시 시도해 주세요.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
