package com.aicreviewer.identity;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAccountService {
    private static final RowMapper<UserAccount> ACCOUNT = (rs, row) -> account(rs, false);
    private static final RowMapper<UserAccount> ADMIN_ACCOUNT = (rs, row) -> account(rs, true);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditEventWriter audit;

    public UserAccountService(JdbcTemplate jdbc, PasswordEncoder passwords, AuditEventWriter audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
    }

    public UserAccount requireAccount(String username) {
        if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jdbc.query("SELECT * FROM app_user WHERE username = ? AND enabled = TRUE AND approval_status = 'APPROVED'", ACCOUNT,
                username.toLowerCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public UserAccount requireAdmin(String username) {
        UserAccount actor = requireAccount(username);
        if (!actor.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }

    public List<UserAccount> list(String actorName) {
        return list(actorName, 0, "", "");
    }

    public List<UserAccount> list(String actorName, int page, String status, String search) {
        requireAdmin(actorName);
        if (page < 0 || page > 10000 || status == null || !List.of("", "PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String query = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 80 || query.chars().anyMatch(Character::isISOControl)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var arguments = new ArrayList<Object>();
        String sql = "SELECT * FROM app_user WHERE username LIKE ? ESCAPE '!'";
        arguments.add(pattern);
        if (!status.isEmpty()) { sql += " AND approval_status = ?"; arguments.add(status); }
        arguments.add((long) page * 50);
        return jdbc.query(sql + " ORDER BY id DESC LIMIT 51 OFFSET ?", ADMIN_ACCOUNT, arguments.toArray());
    }

    /** Public signup never accepts a requested role or activation state. */
    @Transactional
    public void signup(String username, String password, String gitUsername) {
        String normalizedName = AccountInput.username(username);
        String normalizedGit = AccountInput.gitUsername(gitUsername);
        String hash = passwords.encode(AccountInput.password(password));
        // ON CONFLICT avoids aborting the PostgreSQL transaction on a duplicate request.
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO app_user(username, password_hash, git_username, role, enabled, approval_status)
                    VALUES (?, ?, ?, 'USER', FALSE, 'PENDING') ON CONFLICT DO NOTHING
                    """, new String[] { "id" });
            statement.setString(1, normalizedName);
            statement.setString(2, hash);
            statement.setString(3, normalizedGit);
            return statement;
        }, keys);
        if (inserted == 1) {
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("회원가입 요청 결과를 확인할 수 없습니다.");
            audit.write(null, "USER_SIGNUP_REQUESTED", "USER", key.longValue(), "관리자 승인 대기");
        }
    }

    @Transactional
    public void decideApproval(String actorName, long targetId, String action, String reason) {
        UserAccount actor = requireAdmin(actorName);
        List<UserAccount> accounts = jdbc.query("SELECT * FROM app_user WHERE id = ? FOR UPDATE", ADMIN_ACCOUNT, targetId);
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        UserAccount target = accounts.getFirst();
        String next = switch (action) {
            case "approve" -> "APPROVED";
            case "reject" -> "REJECTED";
            case "reopen" -> "PENDING";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        String expected = "reopen".equals(action) ? "REJECTED" : "PENDING";
        if (!expected.equals(target.approvalStatus()) || !"USER".equals(target.role())) {
            throw new IllegalArgumentException("현재 승인 상태에서는 요청한 변경을 할 수 없습니다.");
        }
        String explanation = reason == null ? "" : reason.strip();
        if (explanation.length() > 500 || explanation.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("반려 사유는 제어문자 없이 500자 이하로 입력해 주세요.");
        }
        jdbc.update("""
                UPDATE app_user SET approval_status = ?, enabled = ?, security_version = security_version + 1,
                    approval_decided_at = CASE WHEN ? = 'PENDING' THEN NULL ELSE CURRENT_TIMESTAMP END,
                    approval_reason = ? WHERE id = ?
                """, next, "APPROVED".equals(next), next,
                "REJECTED".equals(next) && !explanation.isEmpty() ? explanation : null, targetId);
        String detail = expected + " → " + next;
        if ("REJECTED".equals(next)) {
            detail += "; 반려 사유: " + approvalReasonDetail(explanation);
        } else if ("REJECTED".equals(expected)) {
            // Capture the current reason before clearing it, including reasons saved
            // by older versions that did not include them in the rejection audit.
            detail += "; 이전 반려 사유: " + approvalReasonDetail(target.approvalReason());
        }
        audit.write(actor.id(), "USER_APPROVAL_" + next, "USER", targetId, detail);
    }

    @Transactional
    public long create(String actorName, String username, String password, String gitUsername, String role) {
        UserAccount actor = requireAdmin(actorName);
        return insert(actor.id(), username, password, gitUsername, role, "USER_CREATED");
    }

    @Transactional
    public void bootstrap(String username, String password, String gitUsername) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        if (count != null && count > 0) return;
        if (username == null || username.isBlank() || password == null || password.isBlank()
                || gitUsername == null || gitUsername.isBlank()) {
            throw new IllegalStateException("최초 관리자 생성에 APP_BOOTSTRAP_USERNAME, APP_BOOTSTRAP_PASSWORD, APP_BOOTSTRAP_GIT_USERNAME 설정이 필요합니다.");
        }
        insert(null, username, password, gitUsername, "ADMIN", "ADMIN_BOOTSTRAPPED");
    }

    private long insert(Long actorId, String username, String password, String gitUsername, String role, String action) {
        String normalizedName = AccountInput.username(username);
        String normalizedGit = AccountInput.gitUsername(gitUsername);
        String validRole = AccountInput.role(role);
        String hash = passwords.encode(AccountInput.password(password));
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO app_user(username, password_hash, git_username, role, enabled, created_at)
                        VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)
                        """, new String[] { "id" });
                statement.setString(1, normalizedName);
                statement.setString(2, hash);
                statement.setString(3, normalizedGit);
                statement.setString(4, validRole);
                return statement;
            }, keys);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("이미 사용 중인 아이디 또는 Git 계정입니다.");
        }
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("사용자 생성 결과를 확인할 수 없습니다.");
        long id = key.longValue();
        audit.write(actorId, action, "USER", id, "권한: " + validRole);
        return id;
    }

    @Transactional
    public void setEnabled(String actorName, long targetId, boolean enabled) {
        UserAccount actor = requireAdmin(actorName);
        // Lock in a stable order so concurrent administrators cannot disable the last two administrators.
        List<UserAccount> admins = jdbc.query("SELECT * FROM app_user WHERE role = 'ADMIN' ORDER BY id FOR UPDATE", ACCOUNT);
        UserAccount target = find(targetId);
        requireApproved(target);
        if (!enabled && target.isAdmin() && target.enabled()
                && admins.stream().filter(UserAccount::enabled).count() <= 1) {
            throw new IllegalArgumentException("마지막 활성 관리자는 비활성화할 수 없습니다.");
        }
        jdbc.update("UPDATE app_user SET enabled = ?, security_version = security_version + 1 WHERE id = ?", enabled, targetId);
        audit.write(actor.id(), enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", targetId, "");
    }

    @Transactional
    public void resetPassword(String actorName, long targetId, String newPassword) {
        UserAccount actor = requireAdmin(actorName);
        requireApproved(find(targetId));
        String encoded = passwords.encode(AccountInput.password(newPassword));
        jdbc.update("UPDATE app_user SET password_hash = ?, security_version = security_version + 1 WHERE id = ?", encoded, targetId);
        audit.write(actor.id(), "USER_PASSWORD_RESET", "USER", targetId, "기존 로그인 세션 만료");
    }

    public UserAccount forAdmin(String actorName, long targetId) {
        requireAdmin(actorName);
        return find(targetId);
    }

    /** A correction changes future author matching, never existing issue ownership. */
    @Transactional
    public void changeGitUsername(String actorName, long targetId, String expectedGitUsername, String newGitUsername) {
        requireAdmin(actorName);
        String replacement;
        try {
            replacement = AccountInput.gitUsername(newGitUsername);
        } catch (IllegalArgumentException invalid) {
            throw new GitUsernameChangeException(HttpStatus.BAD_REQUEST, "Git 계정은 @ 없이 영문, 숫자, 점, 밑줄, 하이픈으로 1~100자 입력해 주세요.");
        }
        if (recognizableGitCredential(replacement)) {
            throw new GitUsernameChangeException(HttpStatus.BAD_REQUEST, "토큰이나 비밀번호 대신 공개 Git 사용자명을 입력해 주세요.");
        }
        var targets = jdbc.query("SELECT * FROM app_user WHERE id = ? FOR UPDATE", ACCOUNT, targetId);
        if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        UserAccount target = targets.getFirst();
        // Compare the exact value the administrator saw; do not silently normalize a stale form.
        if (!target.gitUsername().equals(expectedGitUsername)) {
            throw new GitUsernameChangeException(HttpStatus.CONFLICT, "다른 관리자가 Git 계정을 변경했습니다. 아래 현재 값을 확인하고 다시 저장해 주세요.");
        }
        if (target.gitUsername().equals(replacement)) {
            throw new GitUsernameChangeException(HttpStatus.BAD_REQUEST, "현재와 다른 Git 사용자명을 입력해 주세요.");
        }
        // The row lock may have waited while another administrator revoked the actor.
        UserAccount actor = requireAdmin(actorName);
        try {
            jdbc.update("UPDATE app_user SET git_username = ? WHERE id = ?", replacement, targetId);
        } catch (DuplicateKeyException duplicate) {
            throw new GitUsernameChangeException(HttpStatus.CONFLICT, "이미 다른 계정이 사용 중인 Git 사용자명입니다. 해당 계정을 확인해 주세요.");
        }
        audit.write(actor.id(), "USER_GIT_USERNAME_CHANGED", "USER", targetId,
                "Git 계정 정정; 이전=" + safeGitUsernameValue(target.gitUsername()) + "; 변경=" + replacement);
    }

    /** Only bounded account-shaped values may be reflected after a failed correction. */
    static String safeGitUsernameValue(String value) {
        if (value == null || value.length() > 100 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")
                || recognizableGitCredential(value)) return "";
        return value;
    }

    private static boolean recognizableGitCredential(String value) {
        // A prefix alone can be a valid account name. Exclude only recognizable
        // long credential shapes; arbitrary secrets cannot be identified here.
        return value.matches("(?i)(?:sk-(?:proj-|svcacct-)[a-z0-9_-]{20,}|sk-[a-z0-9]{40,}|gh[pousr]_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|glpat-[a-z0-9_-]{20,})");
    }

    public static final class GitUsernameChangeException extends RuntimeException {
        private final HttpStatus status;
        private GitUsernameChangeException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
        public HttpStatus status() { return status; }
    }

    @Transactional
    public void changePassword(String actorName, String currentPassword, String newPassword) {
        UserAccount actor = requireAccount(actorName);
        String existing = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ? FOR UPDATE",
                String.class, actor.id());
        if (currentPassword == null || !passwords.matches(currentPassword, existing)) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }
        AccountInput.password(newPassword);
        if (passwords.matches(newPassword, existing)) throw new IllegalArgumentException("현재와 다른 새 비밀번호를 입력해 주세요.");
        jdbc.update("UPDATE app_user SET password_hash = ?, security_version = security_version + 1 WHERE id = ?", passwords.encode(newPassword), actor.id());
        audit.write(actor.id(), "PASSWORD_CHANGED", "USER", actor.id(), "기존 로그인 세션 만료");
    }

    private UserAccount find(long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", ACCOUNT, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static UserAccount account(ResultSet rs, boolean adminDetails) throws SQLException {
        String approvalStatus = rs.getString("approval_status");
        return new UserAccount(rs.getLong("id"), rs.getString("username"), rs.getString("git_username"),
                rs.getString("role"), rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(), approvalStatus,
                adminDetails && "REJECTED".equals(approvalStatus) ? rs.getString("approval_reason") : null);
    }

    private static String approvalReasonDetail(String reason) {
        return reason == null || reason.isBlank() ? "(입력하지 않음)" : reason;
    }

    private static void requireApproved(UserAccount account) {
        if (!account.approved()) throw new IllegalArgumentException("승인 완료된 계정만 이용 상태나 비밀번호를 변경할 수 있습니다.");
    }
}
