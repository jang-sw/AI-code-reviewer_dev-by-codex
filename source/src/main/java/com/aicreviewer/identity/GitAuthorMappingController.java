package com.aicreviewer.identity;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class GitAuthorMappingController {
    private final GitAuthorMappingService mappings;

    public GitAuthorMappingController(GitAuthorMappingService mappings) { this.mappings = mappings; }

    @GetMapping("/admin/git-authors")
    public String list(Principal principal, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "") String userSearch, Model model) {
        var results = mappings.list(principal.getName(), page);
        var options = mappings.userOptions(principal.getName(), userSearch);
        model.addAttribute("mappings", results.size() > 50 ? results.subList(0, 50) : results);
        model.addAttribute("hasNext", results.size() > 50);
        model.addAttribute("page", page);
        model.addAttribute("userOptions", options.size() > 50 ? options.subList(0, 50) : options);
        model.addAttribute("hasMoreUsers", options.size() > 50);
        model.addAttribute("userSearch", userSearch);
        model.addAttribute("pageTitle", "Git 작성자 매핑");
        return "admin/git-authors";
    }

    @PostMapping("/admin/git-authors")
    public String create(Principal principal, @RequestParam long userId, @RequestParam String repositoryOrigin,
                         @RequestParam String authorEmail, RedirectAttributes redirect) {
        try {
            mappings.create(principal.getName(), userId, repositoryOrigin, authorEmail);
            redirect.addFlashAttribute("notice", "Git 작성자 매핑을 등록했습니다. 이후 생성되는 이슈부터 적용됩니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/git-authors";
    }

    @PostMapping("/admin/git-authors/{id}/delete")
    public String delete(Principal principal, @PathVariable long id, RedirectAttributes redirect) {
        mappings.delete(principal.getName(), id);
        redirect.addFlashAttribute("notice", "Git 작성자 매핑을 삭제했습니다. 기존 이슈의 담당자는 유지됩니다.");
        return "redirect:/admin/git-authors";
    }
}
