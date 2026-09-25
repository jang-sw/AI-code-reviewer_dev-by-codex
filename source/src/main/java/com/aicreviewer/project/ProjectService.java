package com.aicreviewer.project;

import com.aicreviewer.git.RepositoryUrl;
import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccount;
import com.aicreviewer.identity.UserAccountService;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {
    private static final String SELECT = "SELECT p.*, u.username AS owner_username FROM project p JOIN app_user u ON u.id = p.owner_id";
    private static final RowMapper<Project> PROJECT = (rs, row) -> {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new Project(rs.getLong("id"), rs.getString("name"), rs.getString("repository_url"),
                rs.getString("provider"), rs.getString("repository_host"), rs.getString("repository_path"),
                rs.getLong("owner_id"), rs.getString("owner_username"), rs.getString("status"),
                rs.getString("review_branch"), rs.getString("last_reviewed_sha"),
                approvedAt == null ? null : approvedAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final AuditEventWriter audit;
    private final Set<String> allowedHosts;

    public ProjectService(JdbcTemplate jdbc, UserAccountService users, AuditEventWriter audit,
                          @Value("${app.git.allowed-hosts:github.com}") String allowedHosts) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::strip)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public List<Project> list(String username, int page) {
        UserAccount actor = users.requireAccount(username);
        int offset = checkedPage(page) * 50;
        return actor.isAdmin()
                ? jdbc.query(SELECT + " ORDER BY p.created_at DESC, p.id DESC LIMIT 51 OFFSET ?", PROJECT, offset)
                : jdbc.query(SELECT + " WHERE p.owner_id = ? ORDER BY p.created_at DESC, p.id DESC LIMIT 51 OFFSET ?", PROJECT, actor.id(), offset);
    }

    public Project getVisible(String username, long id) {
        UserAccount actor = users.requireAccount(username);
        Project project = get(id);
        if (!actor.isAdmin() && project.ownerId() != actor.id()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return project;
    }

    @Transactional
    public long request(String username, String name, String repositoryUrl, String branch) {
        UserAccount actor = users.requireAccount(username);
        if (repositoryUrl == null || repositoryUrl.length() > 2048) throw new IllegalArgumentException("저장소 URL은 2,048자 이하로 입력해 주세요.");
        RepositoryUrl repository = RepositoryUrl.parse(repositoryUrl, allowedHosts);
        String projectName = name == null ? "" : name.strip();
        if (projectName.isEmpty()) projectName = repository.path().substring(repository.path().lastIndexOf('/') + 1);
        if (projectName.length() > 120 || projectName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("프로젝트 이름은 제어문자 없이 120자 이하로 입력해 주세요.");
        }
        String reviewBranch = validateBranch(branch);
        String finalName = projectName;
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO project(name, repository_url, provider, repository_host, repository_path,
                            owner_id, status, review_branch, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, new String[] { "id" });
                statement.setString(1, finalName);
                statement.setString(2, repository.normalizedUrl());
                statement.setString(3, repository.provider());
                statement.setString(4, repository.host());
                statement.setString(5, repository.path());
                statement.setLong(6, actor.id());
                statement.setString(7, reviewBranch);
                return statement;
            }, keys);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("이미 등록된 저장소입니다. 관리자에게 확인해 주세요.");
        }
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("프로젝트 등록 결과를 확인할 수 없습니다.");
        long id = key.longValue();
        audit.write(actor.id(), "PROJECT_REQUESTED", "PROJECT", id, "관리자 승인 대기 · 최초 전체 이력 리뷰");
        return id;
    }

    @Transactional
    public void transition(String username, long id, String action) {
        UserAccount actor = users.requireAdmin(username);
        List<String> statuses = jdbc.query("SELECT status FROM project WHERE id = ? FOR UPDATE", (rs, row) -> rs.getString(1), id);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String current = statuses.getFirst();
        String next = switch (action) {
            case "approve" -> "APPROVED";
            case "reject" -> "REJECTED";
            case "pause" -> "PAUSED";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        if (next.equals(current)) return;
        if (("reject".equals(action) && !"PENDING".equals(current))
                || ("pause".equals(action) && !"APPROVED".equals(current))) {
            throw new IllegalArgumentException("현재 프로젝트 상태에서는 요청한 변경을 할 수 없습니다.");
        }
        jdbc.update("""
                UPDATE project SET status = ?, approved_at = CASE WHEN ? = 'APPROVED'
                    THEN COALESCE(approved_at, CURRENT_TIMESTAMP) ELSE approved_at END,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, next, next, id);
        audit.write(actor.id(), "PROJECT_" + next, "PROJECT", id, current + " → " + next);
    }

    private Project get(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", PROJECT, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static int checkedPage(int page) {
        if (page < 0 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return page;
    }

    private static String validateBranch(String branch) {
        if (branch == null || branch.isBlank()) return null;
        String value = branch.strip();
        if (value.length() > 255 || value.equals("@") || value.startsWith("-") || value.startsWith("/")
                || value.endsWith("/") || value.endsWith(".") || value.contains("..") || value.contains("@{")
                || value.contains("//") || value.matches(".*[\\s\\x00-\\x1f\\x7f~^:?*\\[\\\\].*")) {
            throw new IllegalArgumentException("리뷰할 브랜치 이름이 올바르지 않습니다.");
        }
        for (String segment : value.split("/")) {
            if (segment.startsWith(".") || segment.endsWith(".lock")) throw new IllegalArgumentException("리뷰할 브랜치 이름이 올바르지 않습니다.");
        }
        return value;
    }
}
