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
        model.addAttribute("total", result.total());
        model.addAttribute("page", result.page());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("filterStatus", filter);
        model.addAttribute("admin", actor.admin());
        return "issues";
    }

    @PostMapping("/issues/{id}/status")
    public String changeStatus(@PathVariable long id, @RequestParam String status, Principal principal, RedirectAttributes redirect) {
        service.changeStatus(id, status, reviews.actor(principal.getName()));
        redirect.addFlashAttribute("message", "이슈 상태를 변경했습니다.");
        return "redirect:/issues";
    }
}
