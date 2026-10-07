package com.aicreviewer.identity;

import java.security.Principal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class IdentityController {
    private final UserAccountService users;

    public IdentityController(UserAccountService users) { this.users = users; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/signup")
    public String signup(Model model) {
        model.addAttribute("pageTitle", "회원가입");
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String username, @RequestParam String password,
                         @RequestParam String confirmPassword, @RequestParam String gitUsername,
                         RedirectAttributes redirect) {
        try {
            if (!password.equals(confirmPassword)) throw new IllegalArgumentException("비밀번호 확인이 일치하지 않습니다.");
            users.signup(username, password, gitUsername);
            return "redirect:/signup?submitted";
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            redirect.addFlashAttribute("signupForm", Map.of(
                    "username", signupValue(username, 80), "gitUsername", signupValue(gitUsername, 100)));
            return "redirect:/signup";
        }
    }

    @GetMapping("/admin/users")
    public String users(Principal principal, @RequestParam(defaultValue = "0") int page,
                        @RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "") String search, Model model) {
        String approvalStatus = status == null ? "PENDING" : status;
        var accounts = users.list(principal.getName(), page, approvalStatus, search);
        model.addAttribute("users", accounts.size() > 50 ? accounts.subList(0, 50) : accounts);
        model.addAttribute("hasNext", accounts.size() > 50 && page < 10000);
        model.addAttribute("page", page);
        model.addAttribute("approvalFilter", approvalStatus);
        model.addAttribute("search", search);
        model.addAttribute("pageTitle", "사용자 관리");
        return "admin/users";
    }

    @PostMapping("/admin/users/{id}/{action:approve|reject|reopen}")
    public String decideApproval(Principal principal, @PathVariable long id, @PathVariable String action,
                                 @RequestParam(defaultValue = "") String reason,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "") String search,
                                 @RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
        String returnPath = usersReturnPath(status, search, page);
        try {
            users.decideApproval(principal.getName(), id, action, reason);
            redirect.addFlashAttribute("notice", switch (action) {
                case "approve" -> "가입을 승인했습니다. 사용자가 로그인할 수 있습니다.";
                case "reject" -> "가입 요청을 반려했습니다.";
                default -> "가입 요청을 승인 대기로 되돌렸습니다.";
            });
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:" + returnPath;
    }

    @PostMapping("/admin/users/{id}/enabled")
    public String enabled(Principal principal, @PathVariable long id, @RequestParam boolean enabled,
                          @RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "") String search,
                          @RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
        String returnPath = usersReturnPath(status, search, page);
        try {
            users.setEnabled(principal.getName(), id, enabled);
            redirect.addFlashAttribute("notice", enabled ? "계정을 활성화했습니다." : "계정을 비활성화하고 기존 세션을 만료했습니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:" + returnPath;
    }

    @PostMapping("/admin/users/{id}/password")
    public String resetPassword(Principal principal, @PathVariable long id, @RequestParam String newPassword,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "") String search,
                                @RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
        String returnPath = usersReturnPath(status, search, page);
        try {
            users.resetPassword(principal.getName(), id, newPassword);
            redirect.addFlashAttribute("notice", "비밀번호를 초기화하고 기존 세션을 만료했습니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:" + returnPath;
    }

    @PostMapping("/admin/users/{id}/git-username")
    public String changeGitUsername(Principal principal, @PathVariable long id,
                                    @RequestParam String expectedGitUsername, @RequestParam String newGitUsername,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "") String search,
                                    @RequestParam(defaultValue = "0") int page, Model model,
                                    HttpServletResponse response, RedirectAttributes redirect) {
        String returnPath = usersReturnPath(status, search, page);
        try {
            users.changeGitUsername(principal.getName(), id, expectedGitUsername, newGitUsername);
            redirect.addFlashAttribute("notice", "Git 사용자명을 정정했습니다. 기존 이슈의 담당자는 유지됩니다.");
            return "redirect:" + returnPath;
        } catch (UserAccountService.GitUsernameChangeException invalid) {
            // Recheck current administrator access after the mutation transaction has rolled back.
            UserAccount current = users.forAdmin(principal.getName(), id);
            users(principal, page, status, search, model);
            model.addAttribute("gitCorrectionTarget", current);
            model.addAttribute("gitCorrectionValue", UserAccountService.safeGitUsernameValue(newGitUsername));
            model.addAttribute("gitCorrectionError", invalid.getMessage());
            response.setStatus(invalid.status().value());
            return "admin/users";
        }
    }

    private static String signupValue(String value, int maximumLength) {
        if (value == null) return "";
        String clean = value.strip().replaceAll("\\p{Cntrl}", "");
        return clean.substring(0, Math.min(clean.length(), maximumLength));
    }

    /** Validate navigation state before changing an account; never accept a caller-supplied URL. */
    private static String usersReturnPath(String status, String search, int page) {
        String filter = status == null ? "PENDING" : status;
        if (page < 0 || page > 10000 || !List.of("", "PENDING", "APPROVED", "REJECTED").contains(filter)
                || search == null || search.length() > 80 || search.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String query = search.strip();
        if (status == null && query.isEmpty() && page == 0) return "/admin/users";
        return "/admin/users?status=" + filter + "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&page=" + page;
    }

    @GetMapping("/account/password")
    public String password(Model model) {
        model.addAttribute("pageTitle", "비밀번호 변경");
        return "account/password";
    }

    @PostMapping("/account/password")
    public String changePassword(Authentication authentication, @RequestParam String currentPassword,
                                 @RequestParam String newPassword, @RequestParam String confirmPassword,
                                 HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        try {
            if (!newPassword.equals(confirmPassword)) throw new IllegalArgumentException("새 비밀번호 확인이 일치하지 않습니다.");
            users.changePassword(authentication.getName(), currentPassword, newPassword);
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            return "redirect:/login?changed";
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/account/password";
        }
    }
}
