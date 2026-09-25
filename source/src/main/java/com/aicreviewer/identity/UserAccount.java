package com.aicreviewer.identity;

import java.time.Instant;

/** An account view that deliberately never exposes a password hash. */
public record UserAccount(long id, String username, String gitUsername, String role,
                          boolean enabled, Instant createdAt, String approvalStatus) {
    public UserAccount(long id, String username, String gitUsername, String role, boolean enabled, Instant createdAt) {
        this(id, username, gitUsername, role, enabled, createdAt, "APPROVED");
    }
    public long getId() { return id; }
    public String getUsername() { return username; }
    public String getGitUsername() { return gitUsername; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean admin() { return "ADMIN".equals(role); }
    public boolean isAdmin() { return admin(); }
    public boolean approved() { return "APPROVED".equals(approvalStatus); }
}
