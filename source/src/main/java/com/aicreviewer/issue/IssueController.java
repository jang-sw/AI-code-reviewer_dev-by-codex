package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.regex.Pattern;

@Controller
public class IssueController {
    // Avoid redisplaying recognizable credentials; this is not a general secret detector.
    private static final Pattern CREDENTIAL_INPUT = Pattern.compile(
            "(?i)(?:password|passwd|pwd|token|secret|api[ _-]?key|authorization|비밀번호|토큰)\\s*[:=]"
                    + "|\\b(?:sk-|gh[pousr]_|github_pat_|glpat-)[a-z0-9_-]+"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+\\S+|https?://[^\\s/]*@");
    private final IssueService service;
    private final ReviewRepository reviews;

    public IssueController(IssueService service, ReviewRepository reviews) {
        this.service = service;
        this.reviews = reviews;
    }

    @GetMapping("/issues")
    public String issues(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                         Principal principal, Model model) {
        var actor = reviews.actor(principal.getName());
        String filter = status == null ? "OPEN" : status;
        var result = service.list(actor, filter, page);
        model.addAttribute("pageTitle", "내부 이슈함");
        model.addAttribute("issues", result.issues());
        model.addAttribute("page", result.page());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("filterStatus", filter);
        model.addAttribute("admin", actor.admin());
        return "issues";
    }

    @GetMapping("/issues/{id}")
    public String detail(@PathVariable long id, @RequestParam(required = false) String filterStatus,
                         @RequestParam(defaultValue = "0") int page, Principal principal, Model model) {
        filterStatus = filterStatus == null ? "OPEN" : filterStatus;
        IssueService.validateFilter(filterStatus, page);
        var issue = service.detail(id, reviews.actor(principal.getName()));
        model.addAttribute("issue", issue);
        model.addAttribute("pageTitle", "MANUAL_REVIEW".equals(issue.get("issue_kind")) ? "수동 확인 업무" : "수정 권고 확인");
        model.addAttribute("filterStatus", filterStatus);
        model.addAttribute("page", page);
        return "issue-detail";
    }

    @PostMapping("/issues/{id}/status")
    public String changeStatus(@PathVariable long id, @RequestParam String status,
                               @RequestParam(required = false) String filterStatus,
                               @RequestParam(defaultValue = "") String reason,
                               @RequestParam(defaultValue = "0") int page, Principal principal, RedirectAttributes redirect,
                               Model model, HttpServletResponse response) {
        filterStatus = filterStatus == null ? "OPEN" : filterStatus;
        IssueService.validateFilter(filterStatus, page);
        try {
            service.changeStatus(id, status, reviews.actor(principal.getName()), reason);
        } catch (ManualIssueReasonException exception) {
            // Recheck access after the failed transaction before returning any issue or submitted input.
            String view = detail(id, filterStatus, page, principal, model);
            String safeReason = safeReasonValue(reason);
            model.addAttribute("reasonError", ManualIssueReasonException.SAFE_MESSAGE);
            model.addAttribute("reasonFormValue", safeReason);
            model.addAttribute("reasonFormCleared", reason != null && !reason.isEmpty() && safeReason.isEmpty());
            model.addAttribute("requestedIssueStatus", status);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return view;
        }
        redirect.addFlashAttribute("message", "이슈 처리 상태와 기록을 저장했습니다.");
        redirect.addAttribute("status", filterStatus);
        redirect.addAttribute("page", page);
        return "redirect:/issues";
    }

    private static String safeReasonValue(String reason) {
        if (reason == null || reason.length() > 1000 || reason.codePoints().anyMatch(Character::isISOControl)
                || CREDENTIAL_INPUT.matcher(reason).find()) return "";
        return reason;
    }
}
