package com.aicreviewer.review;

import com.aicreviewer.git.GitCommitLink;
import com.aicreviewer.web.ReviewRequestView;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;

@Controller
public class ReviewController {
    private final ReviewRepository repository;
    private final ReviewDispatcher dispatcher;
    private final ReviewRequestRepository requests;

    public ReviewController(ReviewRepository repository, ReviewDispatcher dispatcher, ReviewRequestRepository requests) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.requests = requests;
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
    public String reviews(@RequestParam long projectId, @RequestParam(defaultValue = "0") int commitPage,
                          @RequestParam(defaultValue = "0") int runPage, Principal principal, Model model) {
        var project = repository.authorizedProject(projectId, repository.actor(principal.getName()));
        var runs = repository.runs(projectId, runPage);
        var commits = repository.reviewedCommits(projectId, commitPage);
        var commitRows = commits.rows().stream().map(commit -> {
            var row = new LinkedHashMap<>(commit);
            row.put("commit_url", GitCommitLink.from(project.repositoryUrl(), (String) commit.get("commit_sha")));
            return row;
        }).toList();
        model.addAttribute("pageTitle", "리뷰 기록");
        model.addAttribute("projectId", project.id());
        model.addAttribute("projectName", project.name());
        model.addAttribute("projectStatus", project.status());
        model.addAttribute("cursor", project.lastReviewedSha());
        requests.find(projectId).ifPresent(request -> {
            model.addAttribute("reviewRequest", ReviewRequestView.from(request, Instant.now()));
            requests.progress(request).ifPresent(progress -> model.addAttribute("reviewProgress", progress));
        });
        model.addAttribute("runs", runs.rows());
        model.addAttribute("runPage", runs.page());
        model.addAttribute("hasNextRunPage", runs.hasNext());
        model.addAttribute("commits", commitRows);
        model.addAttribute("commitPage", commits.page());
        model.addAttribute("hasNextCommitPage", commits.hasNext());
        model.addAttribute("maxHistoryPage", ReviewRepository.MAX_HISTORY_PAGE);
        return "reviews";
    }

    @PostMapping("/projects/{id}/review")
    public String review(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        var result = dispatcher.submitManual(id, principal.getName());
        redirect.addFlashAttribute("message", switch (result) {
            case QUEUED -> "리뷰 요청을 저장했습니다. 서버가 재시작되어도 요청은 유지됩니다. 이 화면을 새로고침하면 진행 결과를 볼 수 있습니다.";
            case ALREADY_QUEUED -> "이미 리뷰가 대기 중이거나 실행 중입니다.";
        });
        return "redirect:/reviews?projectId=" + id;
    }
}
