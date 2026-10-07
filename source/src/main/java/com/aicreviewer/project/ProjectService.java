package com.aicreviewer.project;

import com.aicreviewer.git.RepositoryUrl;
import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccount;
import com.aicreviewer.identity.UserAccountService;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    public static final int PAGE_SIZE = 50;
    public static final int MAX_PAGE = 10000;
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "PAUSED");
    private static final String SELECT = "SELECT p.*, u.username AS owner_username, (SELECT r.status FROM review_run r WHERE r.project_id = p.id ORDER BY r.id DESC LIMIT 1) AS review_status FROM project p JOIN app_user u ON u.id = p.owner_id";
    private static final RowMapper<Project> PROJECT = (rs, row) -> {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new Project(rs.getLong("id"), rs.getString("name"), rs.getString("repository_url"),
                rs.getString("provider"), rs.getString("repository_host"), rs.getString("repository_path"),
                rs.getLong("owner_id"), rs.getString("owner_username"), rs.getString("status"),
                rs.getString("review_branch"), rs.getString("last_reviewed_sha"),
                approvedAt == null ? null : approvedAt.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("review_status"));
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
        return load(users.requireAccount(username), "", "", checkedPage(page));
    }

    public ProjectPage list(String username, String status, String query, int page) {
        UserAccount actor = users.requireAccount(username);
        checkedPage(page);
        String filter = status == null ? "" : status.strip();
        String search = query == null ? "" : query.strip();
        if ((!filter.isEmpty() && !STATUSES.contains(filter)) || search.length() > 120 || search.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로젝트 검색 조건을 확인해 주세요.");
        }
        List<Project> rows = load(actor, filter, search, page);
        return new ProjectPage(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), page,
                rows.size() > PAGE_SIZE && page < MAX_PAGE, filter, search);
    }

    private List<Project> load(UserAccount actor, String status, String query, int page) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        var args = new ArrayList<Object>();
        if (!actor.isAdmin()) { sql.append(" AND p.owner_id = ?"); args.add(actor.id()); }
        if (!status.isEmpty()) { sql.append(" AND p.status = ?"); args.add(status); }
        if (!query.isEmpty()) {
            String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" AND (LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.repository_url) LIKE ? ESCAPE '!')");
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY p.id DESC LIMIT ? OFFSET ?");
        args.add(PAGE_SIZE + 1);
        args.add((long) page * PAGE_SIZE);
        return jdbc.query(sql.toString(), PROJECT, args.toArray());
    }

    public record ProjectPage(List<Project> projects, int page, boolean hasNext, String status, String query) { }

    public Project getVisible(String username, long id) {
        UserAccount actor = users.requireAccount(username);
        Project project = get(id);
        if (!actor.isAdmin() && project.ownerId() != actor.id()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return project;
    }

    @Transactional
    public long request(String username, String name, String repositoryUrl, String branch) {
        UserAccount actor = users.requireAccount(username);
        var errors = new LinkedHashMap<String, String>();
        RepositoryUrl repository = null;
        if (repositoryUrl == null || repositoryUrl.isBlank()) errors.put("repositoryUrl", "Git 저장소 URL을 입력해 주세요.");
        else if (repositoryUrl.length() > 2048) errors.put("repositoryUrl", "저장소 URL은 2,048자 이하로 입력해 주세요.");
        else {
            try { repository = RepositoryUrl.parse(repositoryUrl.strip(), allowedHosts); }
            catch (IllegalArgumentException exception) {
                errors.put("repositoryUrl", "등록 가능한 GitHub 또는 GitLab 저장소 주소를 입력해 주세요. 주소에 비밀번호·토큰을 넣을 수 없습니다.");
            }
        }
        String projectName = name == null ? "" : name.strip();
        if (projectName.isEmpty() && repository != null) projectName = repository.path().substring(repository.path().lastIndexOf('/') + 1);
        if (projectName.length() > 120 || projectName.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("name", "프로젝트 이름은 줄바꿈 없이 120자 이하로 입력해 주세요.");
        }
        String reviewBranch = null;
        try { reviewBranch = validateBranch(branch); }
        catch (IllegalArgumentException exception) { errors.put("reviewBranch", "브랜치 이름을 확인해 주세요. 공백이나 '..' 같은 문자는 사용할 수 없습니다."); }
        if (!errors.isEmpty()) throw new ProjectValidationException(errors);
        RepositoryUrl finalRepository = repository;
        String finalBranch = reviewBranch;
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
                statement.setString(2, finalRepository.normalizedUrl());
                statement.setString(3, finalRepository.provider());
                statement.setString(4, finalRepository.host());
                statement.setString(5, finalRepository.path());
                statement.setLong(6, actor.id());
                statement.setString(7, finalBranch);
                return statement;
            }, keys);
        } catch (DuplicateKeyException exception) {
            throw new ProjectValidationException(java.util.Map.of("repositoryUrl", "이미 등록된 저장소입니다. 관리자에게 확인해 주세요."));
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
        if (page < 0 || page > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return page;
    }

    static String validateBranch(String branch) {
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
