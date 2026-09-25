package com.aicreviewer.project;

import com.aicreviewer.identity.UserAccountService;
import java.security.Principal;
import java.util.List;
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

    public ProjectController(ProjectService projects, UserAccountService users) {
        this.projects = projects;
        this.users = users;
    }

    @GetMapping("/projects")
    public String list(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        List<Project> result = projects.list(principal.getName(), page);
        model.addAttribute("projects", result.size() > 50 ? result.subList(0, 50) : result);
        model.addAttribute("hasNext", result.size() > 50);
        model.addAttribute("page", page);
        model.addAttribute("pageTitle", "프로젝트");
        model.addAttribute("isAdmin", users.requireAccount(principal.getName()).isAdmin());
        return "projects/list";
    }

    @PostMapping("/projects")
    public String request(Principal principal, @RequestParam(defaultValue = "") String name,
                          @RequestParam String repositoryUrl, @RequestParam(defaultValue = "") String reviewBranch,
                          RedirectAttributes redirect) {
        try {
            long id = projects.request(principal.getName(), name, repositoryUrl, reviewBranch);
            redirect.addFlashAttribute("notice", "프로젝트 등록을 요청했습니다. 관리자 승인 후 전체 커밋 이력부터 리뷰합니다.");
            return "redirect:/projects/" + id;
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/projects";
        }
    }

    @GetMapping("/projects/{id}")
    public String detail(Principal principal, @PathVariable long id, Model model) {
        model.addAttribute("project", projects.getVisible(principal.getName(), id));
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
