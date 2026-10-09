package com.aicreviewer.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

class SafeAccessDeniedHandlerTest {
    private final SafeAccessDeniedHandler handler = new SafeAccessDeniedHandler();

    @Test void csrfFailurePassesOnlyABooleanAndNeverTheExpectedOrSubmittedToken() throws Exception {
        for (var exception : new AccessDeniedException[] {
                new MissingCsrfTokenException("submitted-private-fixture"),
                new InvalidCsrfTokenException(new DefaultCsrfToken("header", "parameter", "expected-private-fixture"),
                        "submitted-private-fixture") }) {
            var request = new MockHttpServletRequest("POST", "/projects");
            var response = new MockHttpServletResponse();
            handler.handle(request, response, exception);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getErrorMessage()).isNull();
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(java.util.Collections.list(request.getAttributeNames()))
                    .containsExactly(SafeAccessDeniedHandler.CSRF_FAILURE);
            assertThat(request.getAttribute(SafeAccessDeniedHandler.CSRF_FAILURE)).isEqualTo(Boolean.TRUE);
        }
    }

    @Test void ordinaryForbiddenIsNotMisclassifiedAsAnExpiredForm() throws Exception {
        var request = new MockHttpServletRequest("POST", "/admin/users/1/enabled");
        var response = new MockHttpServletResponse();
        handler.handle(request, response, new AccessDeniedException("private-fixture"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(request.getAttribute(SafeAccessDeniedHandler.CSRF_FAILURE)).isNull();
        assertThat(response.getErrorMessage()).doesNotContain("private-fixture");
    }
}
