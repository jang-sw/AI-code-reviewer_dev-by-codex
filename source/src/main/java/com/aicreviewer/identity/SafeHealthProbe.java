package com.aicreviewer.identity;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The only public operational routes; share this boundary with session revalidation. */
final class SafeHealthProbe {
    static final RequestMatcher REQUESTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/actuator/health/liveness"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, "/actuator/health/liveness"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/actuator/health/readiness"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, "/actuator/health/readiness"));

    private SafeHealthProbe() { }
}
