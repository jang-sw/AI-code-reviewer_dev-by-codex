package com.aicreviewer.identity;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Fixed public guidance only; rejected credentials and caller-provided return URLs never reach the view model. */
final class AuthThrottleResponse {
    enum Form {
        LOGIN("/login", "로그인 요청 안내", "로그인 양식 다시 열기",
                "로그인 시도가 잠시 제한되었습니다", "지금은 로그인을 처리할 수 없습니다"),
        SIGNUP("/signup", "가입 요청 안내", "회원가입 양식 다시 열기",
                "가입 요청이 잠시 제한되었습니다", "지금은 가입 요청을 처리할 수 없습니다");

        final String path;
        final String pageTitle;
        final String recoveryLabel;
        final String limitedTitle;
        final String unavailableTitle;

        Form(String path, String pageTitle, String recoveryLabel, String limitedTitle, String unavailableTitle) {
            this.path = path;
            this.pageTitle = pageTitle;
            this.recoveryLabel = recoveryLabel;
            this.limitedTitle = limitedTitle;
            this.unavailableTitle = unavailableTitle;
        }
    }

    private AuthThrottleResponse() { }

    static void limited(HttpServletRequest request, HttpServletResponse response, Form form, long seconds)
            throws ServletException, IOException {
        render(request, response, form, 429, seconds, form.limitedTitle,
                "요청이 너무 많습니다. " + seconds + "초 후 다시 시도해 주세요. 양식을 다시 열어도 제한은 초기화되지 않으며, 다른 제한 상태에 따라 더 기다릴 수 있습니다.");
    }

    static void unavailable(HttpServletRequest request, HttpServletResponse response, Form form)
            throws ServletException, IOException {
        render(request, response, form, 503, 30, form.unavailableTitle,
                "요청을 처리하지 않았습니다. 잠시 후 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.");
    }

    private static void render(HttpServletRequest request, HttpServletResponse response, Form form,
                               int status, long seconds, String title, String message)
            throws ServletException, IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/html;charset=UTF-8");
        request.setAttribute("statusCode", status);
        request.setAttribute("pageTitle", form.pageTitle);
        request.setAttribute("errorTitle", title);
        request.setAttribute("errorMessage", message);
        request.setAttribute("recoveryPath", form.path);
        request.setAttribute("recoveryLabel", form.recoveryLabel);
        request.getRequestDispatcher("/WEB-INF/jsp/request-error.jsp").forward(request, response);
    }
}
