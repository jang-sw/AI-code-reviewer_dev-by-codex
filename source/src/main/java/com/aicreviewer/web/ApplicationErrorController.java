package com.aicreviewer.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/** Do not render exception messages, URLs, submitted values or stack traces. */
@Controller
public class ApplicationErrorController implements ErrorController {
    @RequestMapping("/error")
    public String error(HttpServletRequest request, HttpServletResponse response, Model model) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer code ? code : 500;
        boolean csrfFailure = status == 403 && Boolean.TRUE.equals(request.getAttribute(SafeAccessDeniedHandler.CSRF_FAILURE));
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("statusCode", status);
        model.addAttribute("pageTitle", "요청 처리 안내");
        model.addAttribute("errorTitle", csrfFailure ? "양식을 다시 열어 주세요" : switch (status) {
            case 400 -> "입력 내용을 확인해 주세요";
            case 401 -> "다시 로그인해 주세요";
            case 403 -> "요청 권한을 확인할 수 없습니다";
            case 404 -> "요청한 항목을 찾을 수 없습니다";
            case 409 -> "현재 상태에서는 처리할 수 없습니다";
            default -> "요청을 처리하지 못했습니다";
        });
        model.addAttribute("errorMessage", csrfFailure
                ? "양식이 오래되었거나 요청 확인 정보가 일치하지 않아 제출 내용을 처리하지 않았습니다. 새 양식을 열어 다시 입력해 주세요. 로그인이 필요하면 먼저 로그인해 주세요."
                : switch (status) {
                    case 400 -> "입력값이나 조회 조건이 올바르지 않습니다. 목록이나 양식을 다시 열고 입력 내용을 확인해 주세요.";
                    case 401 -> "로그인 상태를 확인할 수 없습니다. 다시 로그인한 뒤 요청해 주세요.";
                    case 403 -> "이 요청을 처리할 권한이 없습니다. 계정의 접근 권한을 확인하거나 관리자에게 문의해 주세요.";
                    case 404 -> "항목이 없거나 접근할 수 없습니다. 목록에서 다시 확인해 주세요.";
                    case 409 -> "항목의 상태가 바뀌었거나 다른 처리가 진행 중입니다. 최신 상태를 확인한 뒤 다시 요청해 주세요.";
                    default -> "일시적인 문제로 요청을 완료하지 못했습니다. 잠시 후 다시 확인하고, 문제가 계속되면 관리자에게 문의해 주세요.";
                });
        String recovery = status == 401 ? "/login" : status == 403 && !csrfFailure ? "/" : recoveryPath(request);
        model.addAttribute("recoveryPath", recovery);
        model.addAttribute("recoveryLabel", switch (recovery) {
            case "/login" -> "로그인 화면 열기";
            case "/signup" -> "회원가입 양식 다시 열기";
            case "/account/password" -> "비밀번호 변경 양식 다시 열기";
            case "/projects" -> "프로젝트 목록 열기";
            case "/issues" -> "이슈 목록 열기";
            case "/admin/users" -> "사용자 목록 열기";
            case "/admin/git-authors" -> "작성자 매핑 목록 열기";
            case "/admin/operations" -> "운영 현황 다시 열기";
            case "/admin/audit" -> "감사 기록 목록 열기";
            default -> "대시보드로 돌아가기";
        });
        return "request-error";
    }

    /** Original URI selects a fixed category only: no IDs, queries, return URL or submitted data escape. */
    private static String recoveryPath(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (!(original instanceof String uri)) return "/";
        String context = request.getContextPath();
        if (!context.isEmpty() && !uri.startsWith(context + "/")) return "/";
        String path = uri.substring(context.length());
        return switch (path) {
            case "/login", "/signup", "/account/password", "/projects", "/issues", "/admin/users",
                    "/admin/git-authors", "/admin/operations", "/admin/audit" -> path;
            case "/reviews" -> "/projects";
            default -> {
                if (path.matches("/issues/[0-9]+(?:/status)?")) yield "/issues";
                if (path.matches("/projects/[0-9]+(?:/review)?")
                        || path.matches("/admin/projects/[0-9]+/(?:approve|reject|pause|branch|review-progress/reset)")) yield "/projects";
                if (path.matches("/admin/users/[0-9]+/(?:approve|reject|reopen|enabled|password|git-username)")) yield "/admin/users";
                if (path.matches("/admin/git-authors/[0-9]+/delete")) yield "/admin/git-authors";
                yield "/";
            }
        };
    }
}
