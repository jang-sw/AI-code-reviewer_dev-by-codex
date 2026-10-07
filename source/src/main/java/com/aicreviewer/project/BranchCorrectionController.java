package com.aicreviewer.project;

import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ReviewRequestRepository;
import com.aicreviewer.web.ReviewRequestView;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

@Controller
public class BranchCorrectionController {
    private static final Pattern CREDENTIAL_INPUT = Pattern.compile(
            "(?i)(?:password|passwd|pwd|token|secret|api[ _-]?key|authorization|비밀번호|토큰)\\s*[:=]"
                    + "|\\b(?:sk-|gh[pousr]_|github_pat_|glpat-)[a-z0-9_-]+"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+\\S+|https?://[^\\s/]*@");
    private final BranchCorrectionService correction;
    private final ProjectService projects;
    private final UserAccountService users;
    private final ReviewRequestRepository requests;

    public BranchCorrectionController(BranchCorrectionService correction, ProjectService projects,
                                      UserAccountService users, ReviewRequestRepository requests) {
        this.correction = correction;
        this.projects = projects;
        this.users = users;
        this.requests = requests;
    }

    @PostMapping("/admin/projects/{id}/branch")
    public String correct(@PathVariable long id, @RequestParam(defaultValue = "") String expectedBranch,
                          @RequestParam(defaultValue = "") String expectedCursor, @RequestParam(defaultValue = "") String reviewBranch,
                          @RequestParam(defaultValue = "") String reason, @RequestParam(defaultValue = "false") boolean confirmed,
                          Principal principal, RedirectAttributes redirect, Model model, HttpServletResponse response) {
        try {
            correction.correct(principal.getName(), id, expectedBranch, expectedCursor, reviewBranch, reason, confirmed);
        } catch (BranchCorrectionException exception) {
            users.requireAdmin(principal.getName());
            model.addAttribute("project", projects.getVisible(principal.getName(), id));
            requests.find(id).ifPresent(request -> {
                model.addAttribute("reviewRequest", ReviewRequestView.from(request, Instant.now()));
                requests.progress(request).ifPresent(progress -> model.addAttribute("reviewProgress", progress));
            });
            model.addAttribute("isAdmin", users.requireAdmin(principal.getName()).isAdmin());
            model.addAttribute("pageTitle", "프로젝트 상세");
            String safeBranch = safeValue(reviewBranch, 255);
            String safeReason = safeValue(reason, 500);
            model.addAttribute("branchCorrectionError", exception.safeMessage());
            model.addAttribute("branchCorrectionForm", Map.of("reviewBranch", safeBranch, "reason", safeReason));
            model.addAttribute("branchCorrectionCleared", (!reviewBranch.isEmpty() && safeBranch.isEmpty()) || (!reason.isEmpty() && safeReason.isEmpty()));
            response.setStatus(exception.getStatusCode().value());
            return "projects/detail";
        }
        redirect.addFlashAttribute("notice", "리뷰 브랜치를 정정하고 진행 기준을 초기화했습니다. 기존 리뷰와 이슈는 보존했습니다. 현재 상태를 확인한 뒤 별도로 승인하거나 리뷰를 재개해 주세요.");
        return "redirect:/projects/" + id;
    }

    private static String safeValue(String value, int maximum) {
        return value == null || value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)
                || CREDENTIAL_INPUT.matcher(value).find() ? "" : value;
    }
}
