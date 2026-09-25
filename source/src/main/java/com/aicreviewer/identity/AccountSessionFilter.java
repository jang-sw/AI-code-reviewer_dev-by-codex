package com.aicreviewer.identity;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountUserDetailsService users;

    AccountSessionFilter(AccountUserDetailsService users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            boolean valid;
            try {
                valid = principal.sameCredentials(users.loadUserByUsername(principal.getUsername()));
            } catch (UsernameNotFoundException exception) {
                valid = false;
            }
            if (!valid) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                response.sendRedirect(request.getContextPath() + "/login?expired");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
