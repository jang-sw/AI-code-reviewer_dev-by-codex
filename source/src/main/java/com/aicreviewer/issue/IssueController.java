package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
public class IssueController {
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
                               @RequestParam(defaultValue = "0") int page, Principal principal, RedirectAttributes redirect) {
        filterStatus = filterStatus == null ? "OPEN" : filterStatus;
        IssueService.validateFilter(filterStatus, page);
        service.changeStatus(id, status, reviews.actor(principal.getName()), reason);
        redirect.addFlashAttribute("message", "이슈 처리 상태와 기록을 저장했습니다.");
        redirect.addAttribute("status", filterStatus);
        redirect.addAttribute("page", page);
        return "redirect:/issues";
    }
}
