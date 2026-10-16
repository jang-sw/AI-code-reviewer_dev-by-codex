package com.aicreviewer.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertThat(view.getModel()).containsOnlyKeys("statusCode", "pageTitle", "errorTitle",
                        "errorMessage", "recoveryPath", "recoveryLabel")
                .containsEntry("errorMessage", "일시적인 문제로 요청을 완료하지 못했습니다. 잠시 후 다시 확인하고, 문제가 계속되면 관리자에게 문의해 주세요.")
                .containsEntry("recoveryPath", "/").containsEntry("recoveryLabel", "대시보드로 돌아가기");
        assertThat(view.getModel().toString()).doesNotContain("private-");
    }

    @ParameterizedTest
    @CsvSource({"/signup,/signup,회원가입 양식 다시 열기", "/login,/login,로그인 화면 열기",
            "/account/password,/account/password,비밀번호 변경 양식 다시 열기",
            "/projects/42/review,/projects,프로젝트 목록 열기", "/reviews,/projects,프로젝트 목록 열기",
            "/issues/42/status,/issues,이슈 목록 열기", "/admin/users/42/password,/admin/users,사용자 목록 열기",
            "/admin/users/42/git-username,/admin/users,사용자 목록 열기",
            "/admin/projects/42/review-progress/reset,/projects,프로젝트 목록 열기",
            "/admin/git-authors/42/delete,/admin/git-authors,작성자 매핑 목록 열기",
            "/admin/operations,/admin/operations,운영 현황 다시 열기", "/admin/audit,/admin/audit,감사 기록 목록 열기"})
    void unexpectedFailureUsesOnlyTheCurrentRequestFixedCategoryWithOrWithoutAContext(
            String path, String expectedPath, String expectedLabel) {
        for (String context : List.of("", "/review;<synthetic>")) {
            var request = new MockHttpServletRequest("POST", context + path);
            request.setContextPath(context);
            request.setQueryString("next=https://untrusted.example/private-query&password=private-password");
            request.addParameter("repositoryUrl", "https://private-user:private-password@untrusted.example/repo");
            var view = resolver.resolveException(request, new MockHttpServletResponse(), null,
                    new DataIntegrityViolationException("private-sql-fixture"));
            assertThat(view).isNotNull();
            assertThat(view.getModel()).containsEntry("statusCode", 500)
                    .containsEntry("recoveryPath", expectedPath).containsEntry("recoveryLabel", expectedLabel);
            assertThat(view.getModel().toString()).doesNotContain("42", "synthetic", "untrusted", "private-");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"//untrusted.example/admin/audit", "https://private-user:private-password@untrusted.example/admin/audit",
            "/admin/audit?password=private-query", "/admin/audit#private-fragment", "/%61dmin/audit", "/admin/audit;private",
            "/admin/users/42;private/password", "/admin/users/../audit", "/admin/users/42/unknown", "/unknown"})
    void unknownEncodedOrDecoratedRequestUrisNeverBecomeRecoveryLinks(String uri) {
        var request = new MockHttpServletRequest("GET", uri);
        var view = resolver.resolveException(request, new MockHttpServletResponse(), null,
                new IllegalStateException("private-cause-fixture"));
        assertThat(view).isNotNull();
        assertThat(view.getModel()).containsEntry("recoveryPath", "/").containsEntry("recoveryLabel", "대시보드로 돌아가기");
        assertThat(view.getModel().toString()).doesNotContain("untrusted", "private", "42", "unknown", "%61", "../");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/reviewother/admin/audit", "/other/review/admin/audit", "/review", "//untrusted.example/review/admin/audit"})
    void contextMustMatchTheWholeLeadingPathSegmentBeforeCategorySelection(String uri) {
        var request = new MockHttpServletRequest("GET", uri);
        request.setContextPath("/review");
        var view = resolver.resolveException(request, new MockHttpServletResponse(), null,
                new IllegalStateException("private-cause-fixture"));
        assertThat(view).isNotNull();
        assertThat(view.getModel()).containsEntry("recoveryPath", "/").containsEntry("recoveryLabel", "대시보드로 돌아가기");
        assertThat(view.getModel().toString()).doesNotContain("review", "untrusted", "private");
    }

    @Test
    void errorDispatchAttributesCannotSpoofTheOrdinaryRequestCategoryOrIts500Status() {
        var request = new MockHttpServletRequest("GET", "/review/admin/audit");
        request.setContextPath("/review");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/review/issues/42/status");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private-error-fixture");
        request.setAttribute(SafeAccessDeniedHandler.CSRF_FAILURE, Boolean.TRUE);
        var response = new MockHttpServletResponse();
        var view = resolver.resolveException(request, response, null, new IllegalStateException("private-cause-fixture"));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(view).isNotNull();
        assertThat(view.getModel()).containsEntry("statusCode", 500)
                .containsEntry("recoveryPath", "/admin/audit").containsEntry("recoveryLabel", "감사 기록 목록 열기");
        assertThat(view.getModel().toString()).doesNotContain("issues", "42", "private", "양식을 다시 열어");
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
