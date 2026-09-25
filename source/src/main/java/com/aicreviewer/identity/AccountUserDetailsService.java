package com.aicreviewer.identity;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AccountUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public AccountPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        return jdbc.query("SELECT username, password_hash, role, enabled, security_version FROM app_user WHERE username = ?",
                (rs, row) -> new AccountPrincipal(rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("role"), rs.getBoolean("enabled"), rs.getLong("security_version")), normalized).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("아이디 또는 비밀번호가 일치하지 않습니다."));
    }
}
