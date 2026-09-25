package com.aicreviewer.identity;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Hash snapshot lets password reset and account changes revoke every existing session. */
public final class AccountPrincipal implements UserDetails {
    @Serial private static final long serialVersionUID = 1L;
    private final String username;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;
    private final long securityVersion;

    public AccountPrincipal(String username, String passwordHash, String role, boolean enabled, long securityVersion) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.securityVersion = securityVersion;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }

    boolean sameCredentials(AccountPrincipal current) {
        return current.enabled && securityVersion == current.securityVersion
                && passwordHash.equals(current.passwordHash) && role.equals(current.role);
    }
}
