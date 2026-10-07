package com.aicreviewer.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class GitAuthorMappingController {
    private static final Pattern CREDENTIAL_INPUT = Pattern.compile(
            "(?i)(?:password|passwd|pwd|token|secret|api[ _-]?key|authorization|비밀번호|토큰)\\s*[:=]"
                    + "|\\b(?:sk-|gh[pousr]_|github_pat_|glpat-)[a-z0-9_-]+"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+\\S+|https?://[^\\s/]*@");
    private final GitAuthorMappingService mappings;
    private final UserAccountService users;

    public GitAuthorMappingController(GitAuthorMappingService mappings, UserAccountService users) {
        this.mappings = mappings;
        this.users = users;
    }

    @GetMapping("/admin/git-authors")
    public String list(Principal principal, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "") String userSearch, Model model) {
        var results = mappings.list(principal.getName(), page);
        validateContext(page, userSearch);
        var options = mappings.userOptions(principal.getName(), userSearch);
        model.addAttribute("mappings", results.size() > 50 ? results.subList(0, 50) : results);
        model.addAttribute("hasNext", results.size() > 50 && page < 10000);
        model.addAttribute("page", page);
        model.addAttribute("userOptions", options.size() > 50 ? options.subList(0, 50) : options);
        model.addAttribute("hasMoreUsers", options.size() > 50);
        model.addAttribute("userSearch", userSearch);
        model.addAttribute("mappingForm", Map.of("userId", "", "repositoryOrigin", "", "authorEmail", ""));
        model.addAttribute("pageTitle", "Git 작성자 매핑");
        return "admin/git-authors";
    }

    @PostMapping("/admin/git-authors")
    public String create(Principal principal, @RequestParam(defaultValue = "") String userId,
                         @RequestParam(defaultValue = "") String repositoryOrigin,
                         @RequestParam(defaultValue = "") String authorEmail,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "") String userSearch, RedirectAttributes redirect,
                         Model model, HttpServletResponse response) {
        users.requireAdmin(principal.getName());
        validateContext(page, userSearch);
        long targetId = selectedUserId(userId);
        try {
            mappings.create(principal.getName(), targetId, repositoryOrigin, authorEmail);
            redirect.addFlashAttribute("notice", "Git 작성자 매핑을 등록했습니다. 이후 생성되는 이슈부터 적용됩니다.");
        } catch (GitAuthorMappingValidationException exception) {
            // The failed transaction may have waited: recheck both actor and selected account now.
            String view = list(principal, page, userSearch, model);
            var selected = targetId > 0 ? mappings.activeUserOption(principal.getName(), targetId) : java.util.Optional.<GitAuthorMappingService.UserOption>empty();
            selected.ifPresent(option -> model.addAttribute("mappingSelectedUser", option));
            String safeOrigin = safeOrigin(repositoryOrigin);
            String safeEmail = safeText(authorEmail, 320);
            model.addAttribute("mappingForm", Map.of("userId", selected.map(option -> Long.toString(option.id())).orElse(""),
                    "repositoryOrigin", safeOrigin, "authorEmail", safeEmail));
            model.addAttribute("mappingErrors", exception.fieldErrors());
            model.addAttribute("mappingFormCleared", (!repositoryOrigin.isEmpty() && safeOrigin.isEmpty())
                    || (!authorEmail.isEmpty() && safeEmail.isEmpty()));
            response.setStatus(exception.status().value());
            return view;
        }
        redirectContext(redirect, page, userSearch);
        return "redirect:/admin/git-authors";
    }

    @PostMapping("/admin/git-authors/{id}/delete")
    public String delete(Principal principal, @PathVariable long id,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "") String userSearch, RedirectAttributes redirect) {
        users.requireAdmin(principal.getName());
        validateContext(page, userSearch);
        mappings.delete(principal.getName(), id);
        redirect.addFlashAttribute("notice", "Git 작성자 매핑을 삭제했습니다. 기존 이슈의 담당자는 유지됩니다.");
        redirectContext(redirect, page, userSearch);
        return "redirect:/admin/git-authors";
    }

    private static void validateContext(int page, String userSearch) {
        if (page < 0 || page > 10000 || userSearch == null || userSearch.length() > 80 || userSearch.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static void redirectContext(RedirectAttributes redirect, int page, String userSearch) {
        if (page > 0) redirect.addAttribute("page", page);
        if (!userSearch.isEmpty()) redirect.addAttribute("userSearch", userSearch);
    }

    private static long selectedUserId(String value) {
        if (value == null || value.length() > 19 || !value.matches("[0-9]+")) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static String safeText(String value, int maximum) {
        if (value == null || value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)
                || CREDENTIAL_INPUT.matcher(value).find()) return "";
        return value;
    }

    private static String safeOrigin(String value) {
        String bounded = safeText(value, 512);
        if (bounded.isEmpty()) return "";
        try {
            URI uri = URI.create(bounded.strip());
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) return "";
            return bounded;
        } catch (IllegalArgumentException invalid) { return ""; }
    }
}
