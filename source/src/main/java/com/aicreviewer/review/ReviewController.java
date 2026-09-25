package com.aicreviewer.review;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
public class ReviewController {
    private final ReviewRepository repository;
    private final ReviewDispatcher dispatcher;

    public ReviewController(ReviewRepository repository, ReviewDispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/")
    public String dashboard(Principal principal, Model model) {
        var actor = repository.actor(principal.getName());
        model.addAttribute("pageTitle", "대시보드");
        model.addAttribute("stats", repository.dashboard(actor));
        model.addAttribute("projects", repository.recentProjects(actor));
        return "dashboard";
    }

    @GetMapping("/reviews")
    public String reviews(@RequestParam long projectId, Principal principal, Model model) {
        var project = repository.authorizedProject(projectId, repository.actor(principal.getName()));
        model.addAttribute("pageTitle", "리뷰 기록");
        model.addAttribute("projectId", project.id());
        model.addAttribute("projectName", project.name());
        model.addAttribute("projectStatus", project.status());
        model.addAttribute("cursor", project.lastReviewedSha());
        model.addAttribute("queued", dispatcher.isQueued(projectId));
        model.addAttribute("runs", repository.runs(projectId));
        model.addAttribute("commits", repository.reviewedCommits(projectId));
        return "reviews";
    }

    @PostMapping("/projects/{id}/review")
    public String review(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        var result = dispatcher.submitManual(id, principal.getName());
        redirect.addFlashAttribute("message", switch (result) {
            case QUEUED -> "리뷰를 대기열에 등록했습니다. 이 화면을 새로고침하면 진행 결과를 볼 수 있습니다.";
            case ALREADY_QUEUED -> "이미 리뷰가 대기 중이거나 실행 중입니다.";
            case CAPACITY_REACHED -> "리뷰 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.";
        });
        return "redirect:/reviews?projectId=" + id;
    }
}
