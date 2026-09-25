package com.aicreviewer.review;

import java.security.Principal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ReviewRecoveryController {
    private final ReviewRecoveryService recovery;

    public ReviewRecoveryController(ReviewRecoveryService recovery) { this.recovery = recovery; }

    @PostMapping("/admin/projects/{id}/review-progress/reset")
    public String reset(@PathVariable long id, @RequestParam(defaultValue = "") String repositoryUrl,
                        @RequestParam(defaultValue = "") String expectedCursor, @RequestParam(defaultValue = "") String reason,
                        Principal principal, RedirectAttributes redirect, Model model, HttpServletResponse response) {
        try {
            recovery.resetProgress(principal.getName(), id, repositoryUrl, expectedCursor, reason);
        } catch (ReviewRecoveryException exception) {
            response.setStatus(exception.getStatusCode().value());
            model.addAttribute("pageTitle", "리뷰 진행 기준 복구 안내");
            model.addAttribute("projectId", id);
            model.addAttribute("recoveryErrorMessage", exception.safeMessage());
            return "review-recovery-error";
        }
        redirect.addFlashAttribute("notice", "리뷰 진행 기준을 초기화했습니다. 기존 리뷰와 이슈는 보존했습니다. 현재 브랜치를 확인한 뒤 리뷰를 재개해 주세요.");
        return "redirect:/projects/" + id;
    }
}
