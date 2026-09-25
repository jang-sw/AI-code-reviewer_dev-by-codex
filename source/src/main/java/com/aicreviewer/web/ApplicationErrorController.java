package com.aicreviewer.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/** Do not render exception messages, URLs, submitted values or stack traces. */
@Controller
public class ApplicationErrorController implements ErrorController {
    @RequestMapping("/error")
    public String error(HttpServletRequest request, Model model) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer code ? code : 500;
        model.addAttribute("statusCode", status);
        model.addAttribute("pageTitle", "요청 처리 안내");
        model.addAttribute("errorTitle", switch (status) {
            case 400 -> "입력 내용을 확인해 주세요";
            case 403 -> "요청 권한을 확인할 수 없습니다";
            case 404 -> "요청한 항목을 찾을 수 없습니다";
            case 409 -> "현재 상태에서는 처리할 수 없습니다";
            default -> "요청을 처리하지 못했습니다";
        });
        return "request-error";
    }
}
