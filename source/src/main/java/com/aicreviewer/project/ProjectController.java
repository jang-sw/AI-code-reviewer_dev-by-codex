package com.aicreviewer.project;

import com.aicreviewer.git.RepositoryUrl;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ReviewRequestRepository;
import com.aicreviewer.web.ReviewRequestView;
import java.time.Instant;
import java.security.Principal;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ProjectController {
    private final ProjectService projects;
    private final UserAccountService users;
    private final ReviewRequestRepository requests;

    public ProjectController(ProjectService projects, UserAccountService users, ReviewRequestRepository requests) {
        this.projects = projects;
        this.users = users;
        this.requests = requests;
    }

    @GetMapping("/projects")
    public String list(Principal principal, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String q, Model model) {
        var result = projects.list(principal.getName(), status, q, page);
        model.addAttribute("projects", result.projects());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("page", result.page());
        model.addAttribute("filterStatus", result.status());
        model.addAttribute("query", result.query());
        model.addAttribute("maxPage", ProjectService.MAX_PAGE);
        model.addAttribute("pageTitle", "프로젝트");
        model.addAttribute("isAdmin", users.requireAccount(principal.getName()).isAdmin());
        return "projects/list";
    }

    @PostMapping("/projects")
    public String request(Principal principal, @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String repositoryUrl, @RequestParam(defaultValue = "") String reviewBranch,
                          RedirectAttributes redirect) {
        try {
            long id = projects.request(principal.getName(), name, repositoryUrl, reviewBranch);
            redirect.addFlashAttribute("notice", "프로젝트를 등록했습니다. 관리자 승인을 기다려 주세요. 승인 후 리뷰를 실행할 수 있습니다.");
            return "redirect:/projects/" + id;
        } catch (ProjectValidationException exception) {
            redirect.addFlashAttribute("projectErrors", exception.fieldErrors());
            String safeUrl = safeRepositoryValue(repositoryUrl);
            redirect.addFlashAttribute("projectForm", Map.of("name", safeText(name, 240), "reviewBranch", safeText(reviewBranch, 510), "repositoryUrl", safeUrl));
            redirect.addFlashAttribute("repositoryUrlCleared", repositoryUrl != null && !repositoryUrl.isBlank() && safeUrl.isEmpty());
            return "redirect:/projects#register";
        }
    }

    private static String safeRepositoryValue(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            String clean = value.strip();
            URI uri = URI.create(clean);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) return "";
            RepositoryUrl.parse(clean, Set.of(uri.getHost()));
            return clean;
        } catch (IllegalArgumentException exception) { return ""; }
    }

    private static String safeText(String value, int maximum) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    @GetMapping("/projects/{id}")
    public String detail(Principal principal, @PathVariable long id, Model model) {
        model.addAttribute("project", projects.getVisible(principal.getName(), id));
        requests.find(id).ifPresent(request -> model.addAttribute("reviewRequest", ReviewRequestView.from(request, Instant.now())));
        model.addAttribute("isAdmin", users.requireAccount(principal.getName()).isAdmin());
        model.addAttribute("pageTitle", "프로젝트 상세");
        return "projects/detail";
    }

    @PostMapping("/admin/projects/{id}/{action:approve|reject|pause}")
    public String transition(Principal principal, @PathVariable long id, @PathVariable String action,
                             RedirectAttributes redirect) {
        try {
            projects.transition(principal.getName(), id, action);
            redirect.addFlashAttribute("notice", "프로젝트 상태를 변경했습니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/projects/" + id;
    }
}
