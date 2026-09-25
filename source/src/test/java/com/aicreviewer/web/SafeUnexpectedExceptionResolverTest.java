package com.aicreviewer.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;

class SafeUnexpectedExceptionResolverTest {
    private final SafeUnexpectedExceptionResolver resolver = new SafeUnexpectedExceptionResolver();

    @Test
    void unexpectedFailureReturnsOnlyFixedErrorModelAfterEarlierResolvers() {
        var request = new MockHttpServletRequest("POST", "/private-url-fixture");
        request.addParameter("password", "private-parameter-fixture");
        var response = new MockHttpServletResponse();
        var view = resolver.resolveException(request, response, null,
                new DataIntegrityViolationException("private-sql-fixture"));
        assertThat(resolver.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(view).isNotNull();
        assertThat(view.getViewName()).isEqualTo("request-error");
        assertThat(view.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(view.getModel()).containsOnlyKeys("statusCode", "pageTitle", "errorTitle");
        assertThat(view.getModel().toString()).doesNotContain("private-");
    }

    @Test
    void securityStatusAndWrappedJvmErrorsRemainUnresolvedWithoutChangingResponse() {
        for (Exception exception : new Exception[] {
                new AccessDeniedException("denied"), new BadCredentialsException("denied"),
                new ResponseStatusException(HttpStatus.CONFLICT, "conflict"),
                new ServletException(new AccessDeniedException("wrapped denied")),
                new ServletException(new BadCredentialsException("wrapped authentication")),
                new ServletException(new ResponseStatusException(HttpStatus.BAD_REQUEST)),
                new ServletException(new AssertionError("JVM error fixture")),
                new ServletException(new OutOfMemoryError("JVM error fixture")) }) {
            var response = new MockHttpServletResponse();
            assertThat(resolver.resolveException(new MockHttpServletRequest(), response, null, exception)).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void alreadyCommittedResponseIsNotRenderedAgainOrPropagatedWithSensitiveDetails() throws Exception {
        var response = new MockHttpServletResponse();
        response.flushBuffer();
        var result = resolver.resolveException(new MockHttpServletRequest(), response, null,
                new IllegalStateException("private late failure"));
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200); // An already committed status cannot be changed.
    }
}
