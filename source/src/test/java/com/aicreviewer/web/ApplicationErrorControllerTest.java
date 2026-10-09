package com.aicreviewer.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

class ApplicationErrorControllerTest {
    private final ApplicationErrorController controller = new ApplicationErrorController();

    @ParameterizedTest
    @CsvSource({"400,입력값이나 조회 조건", "401,로그인 상태를 확인할 수 없습니다", "403,처리할 권한이 없습니다",
            "404,항목이 없거나 접근할 수 없습니다", "409,항목의 상태가 바뀌었거나", "500,일시적인 문제"})
    void usesFixedStatusSpecificGuidanceWithoutErrorOrSubmittedValues(int status, String explanation) {
        var request = request(status, "/issues");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private-error-fixture");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private-cause-fixture"));
        request.addParameter("password", "private-password-fixture");
        var model = render(request);
        assertThat(model).containsEntry("statusCode", status);
        assertThat(model.get("errorMessage").toString()).contains(explanation);
        assertThat(model.toString()).doesNotContain("private-error", "private-cause", "private-password");
        assertThat(model).containsEntry("recoveryPath", status == 401 ? "/login" : status == 403 ? "/" : "/issues");
    }

    @Test void forbiddenAdministratorPageReturnsToDashboardInsteadOfRepeatingTheDeniedRoute() {
        var request = request(403, "/admin/audit");
        assertThat(render(request)).containsEntry("recoveryPath", "/")
                .containsEntry("recoveryLabel", "대시보드로 돌아가기");
        request.setAttribute(SafeAccessDeniedHandler.CSRF_FAILURE, Boolean.TRUE);
        assertThat(render(request)).containsEntry("recoveryPath", "/admin/audit")
                .containsEntry("errorTitle", "양식을 다시 열어 주세요");
    }

    @Test void csrfClassificationRequiresBooleanAnd403AndDoesNotAssertSessionExpiration() {
        var request = request(403, "/account/password");
        request.setAttribute(SafeAccessDeniedHandler.CSRF_FAILURE, Boolean.TRUE);
        var model = render(request);
        assertThat(model).containsEntry("errorTitle", "양식을 다시 열어 주세요")
                .containsEntry("recoveryPath", "/account/password");
        assertThat(model.get("errorMessage").toString()).contains("제출 내용을 처리하지 않았습니다", "새 양식")
                .doesNotContain("세션이 만료되었습니다");
        request.setAttribute(SafeAccessDeniedHandler.CSRF_FAILURE, "true");
        assertThat(render(request)).containsEntry("errorTitle", "요청 권한을 확인할 수 없습니다");
        request.setAttribute(SafeAccessDeniedHandler.CSRF_FAILURE, Boolean.TRUE);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        assertThat(render(request)).containsEntry("errorTitle", "입력 내용을 확인해 주세요");
    }

    @ParameterizedTest
    @CsvSource({"/signup,/signup", "/login,/login", "/projects,/projects", "/reviews,/projects",
            "/issues/42/status,/issues", "/projects/42/review,/projects",
            "/admin/projects/42/review-progress/reset,/projects", "/admin/projects/42/branch,/projects",
            "/admin/users/42/password,/admin/users", "/admin/git-authors/42/delete,/admin/git-authors",
            "/admin/operations,/admin/operations", "/admin/audit,/admin/audit"})
    void routesOnlyToFixedCategoriesAndRemovesIdsAndQuery(String original, String expected) {
        var request = request(400, original);
        request.setQueryString("next=https://untrusted.example/private&password=private-query-fixture");
        assertThat(render(request)).containsEntry("recoveryPath", expected);
        assertThat(render(request).toString()).doesNotContain("untrusted", "private-query", "42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"//untrusted.example/issues", "https://untrusted.example/issues", "/issues;private",
            "/issues/42;private/status", "/%69ssues", "/issues?password=private", "/admin/users/42/unknown",
            "/projects/<script>private</script>", "/admin/users/../issues"})
    void unrecognizedOrDecoratedPathsNeverBecomeHref(String original) {
        var model = render(request(400, original));
        assertThat(model).containsEntry("recoveryPath", "/");
        assertThat(model.toString()).doesNotContain("untrusted", "private", "<script>");
    }

    @Test void contextPrefixIsRemovedExactlyWithoutReflectingSpecialCharacters() {
        var request = request(400, "/review;<synthetic>/issues/42/status");
        request.setContextPath("/review;<synthetic>");
        assertThat(render(request)).containsEntry("recoveryPath", "/issues");
        assertThat(render(request).toString()).doesNotContain("synthetic", "42");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/review;<synthetic>other/issues");
        assertThat(render(request)).containsEntry("recoveryPath", "/");
    }

    private MockHttpServletRequest request(int status, String original) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, original);
        return request;
    }

    private ExtendedModelMap render(MockHttpServletRequest request) {
        var response = new MockHttpServletResponse();
        var model = new ExtendedModelMap();
        assertThat(controller.error(request, response, model)).isEqualTo("request-error");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return model;
    }
}
