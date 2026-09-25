package com.aicreviewer.identity;

import java.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class IdentityController {
    private final UserAccountService users;

    public IdentityController(UserAccountService users) { this.users = users; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/admin/users")
    public String users(Principal principal, Model model) {
        model.addAttribute("users", users.list(principal.getName()));
        model.addAttribute("pageTitle", "사용자 관리");
        return "admin/users";
    }

    @PostMapping("/admin/users")
    public String create(Principal principal, @RequestParam String username, @RequestParam String password,
                         @RequestParam String gitUsername, @RequestParam(defaultValue = "USER") String role,
                         RedirectAttributes redirect) {
        try {
            users.create(principal.getName(), username, password, gitUsername, role);
            redirect.addFlashAttribute("notice", "사용자 계정을 생성했습니다. 임시 비밀번호는 안전한 경로로 전달해 주세요.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/enabled")
    public String enabled(Principal principal, @PathVariable long id, @RequestParam boolean enabled,
                          RedirectAttributes redirect) {
        try {
            users.setEnabled(principal.getName(), id, enabled);
            redirect.addFlashAttribute("notice", enabled ? "계정을 활성화했습니다." : "계정을 비활성화하고 기존 세션을 만료했습니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/password")
    public String resetPassword(Principal principal, @PathVariable long id, @RequestParam String newPassword,
                                RedirectAttributes redirect) {
        try {
            users.resetPassword(principal.getName(), id, newPassword);
            redirect.addFlashAttribute("notice", "비밀번호를 초기화하고 기존 세션을 만료했습니다.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/users";
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
