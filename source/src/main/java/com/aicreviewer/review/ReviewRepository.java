package com.aicreviewer.review;

import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.ManualReviewFile;
import com.aicreviewer.git.IntegrationException;
import com.aicreviewer.git.RepositoryOrigin;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Repository
public class ReviewRepository {
    public static final int HISTORY_PAGE_SIZE = 50;
    public static final int MAX_HISTORY_PAGE = 10000;
    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReviewActor actor(String username) {
        if (username == null) throw new AccessDeniedException("로그인이 필요합니다.");
        var actors = jdbc.query("select id, username, role from app_user where username = ? and enabled = true",
                (rs, row) -> new ReviewActor(rs.getLong("id"), rs.getString("username"), "ADMIN".equals(rs.getString("role"))), username);
        if (actors.size() != 1) throw new AccessDeniedException("활성 계정이 필요합니다.");
        return actors.getFirst();
    }

    public ReviewProject project(long projectId, boolean forUpdate) {
        var projects = jdbc.query("select * from project where id = ?" + (forUpdate ? " for update" : ""),
                (rs, row) -> new ReviewProject(rs.getLong("id"), rs.getString("name"), rs.getString("repository_url"),
                        rs.getString("provider"), rs.getString("repository_host"), rs.getString("repository_path"),
                        rs.getLong("owner_id"), rs.getString("status"), rs.getString("review_branch"), rs.getString("last_reviewed_sha")), projectId);
        if (projects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
        return projects.getFirst();
    }

    public ReviewProject authorizedProject(long projectId, ReviewActor actor) {
        var project = project(projectId, false);
        if (!actor.admin() && project.ownerId() != actor.id()) throw new AccessDeniedException("프로젝트 접근 권한이 없습니다.");
        return project;
    }

    public List<Long> approvedProjectIds(int limit) {
        if (limit < 1 || limit > ReviewDispatcher.MAX_SCHEDULE_CANDIDATES) {
            throw new IllegalArgumentException("Scheduled candidate limit exceeds dispatcher capacity");
        }
        return jdbc.queryForList("select p.id from project p where p.status = 'APPROVED' order by (select max(r.started_at) from review_run r where r.project_id = p.id) asc nulls first, p.id limit ?", Long.class, limit);
    }

    public long startRun(long projectId, Long actorId, Instant now) {
        // A held session lock proves prior RUNNING rows belong to interrupted processes.
        jdbc.update("update review_run set status = 'FAILED', finished_at = ?, error_message = ? where project_id = ? and status = 'RUNNING'",
                Timestamp.from(now), "이전 실행이 중단되었습니다. 저장된 커밋 리뷰를 재사용하여 다시 진행합니다.", projectId);
        long id = insert("insert into review_run(project_id, status, started_at, reviewed_commits) values (?, 'RUNNING', ?, 0)",
                projectId, Timestamp.from(now));
        audit(actorId, "REVIEW_STARTED", "REVIEW_RUN", id, "project=" + projectId, now);
        return id;
    }

    public boolean alreadyReviewed(long projectId, String sha) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from reviewed_commit where project_id = ? and commit_sha = ?)", Boolean.class, projectId, sha));
    }

    /** The SQL cap bounds JDBC materialization as well as the returned durable-progress set. */
    public Set<String> reviewedShas(long projectId) {
        List<String> rows = jdbc.queryForList("select commit_sha from reviewed_commit where project_id = ? order by id limit ?",
                String.class, projectId, GitRepositoryClient.MAX_REVIEWED_SHAS + 1);
        if (rows.size() > GitRepositoryClient.MAX_REVIEWED_SHAS) {
            throw new IntegrationException("Durable review progress exceeds the configured memory safety budget");
        }
        var shas = new HashSet<String>();
        for (String sha : rows) {
            if (sha == null || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new IntegrationException("Durable review progress contains an invalid commit identifier");
            }
            shas.add(sha);
        }
        return Set.copyOf(shas);
    }

    /** Caller wraps this entire operation in one database transaction. */
    public boolean persistCommit(long runId, ReviewProject original, String expectedCursor,
                                 GitCommit commit, ReviewResult review, Instant now) {
        ReviewProject current = project(original.id(), true);
        requireApproved(current);
        if (!Objects.equals(current.lastReviewedSha(), expectedCursor)) {
            throw new IllegalStateException("Review cursor changed during the run");
        }
        if (alreadyReviewed(current.id(), commit.sha())) return false;
        if ("MANUAL_ONLY".equals(commit.coverageType()) && !review.findings().isEmpty()) {
            throw new IllegalArgumentException("Manual-only commits cannot contain AI findings");
        }
        String email = commit.authorEmail() == null || commit.authorEmail().isBlank() ? null : commit.authorEmail().strip().toLowerCase(Locale.ROOT);
        long commitId = insert("insert into reviewed_commit(project_id, commit_sha, author_login, author_email, summary, coverage_type, coverage_details, reviewed_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                current.id(), commit.sha(), commit.authorLogin(), email, review.summary(), commit.coverageType(), commit.coverageDetails(), Timestamp.from(now));
        Assignment assignment = resolveAssignment(current, commit.authorLogin(), email);
        boolean hasIssues = !review.findings().isEmpty() || !commit.manualFiles().isEmpty();
        if (hasIssues) {
            // The commit's email is personal metadata: only IDs and the assignment reason
            // belong in the audit stream. Issue readers do not receive raw author emails.
            audit(null, "ISSUES_ASSIGNED", "REVIEWED_COMMIT", commitId,
                    "reason=" + assignment.reason() + "; assignee=" + assignment.userId() +
                            (assignment.mappingId() == null ? "" : "; mapping=" + assignment.mappingId()), now);
        }
        if ("PROJECT_OWNER_FALLBACK".equals(assignment.reason()) && hasIssues) {
            audit(null, "ISSUE_ASSIGNEE_FALLBACK", "REVIEWED_COMMIT", commitId,
                    "Git 작성자와 일치하는 활성 계정이나 매핑이 없어 프로젝트 소유자에게 배정했습니다. assignee=" + assignment.userId(), now);
        }
        for (ReviewFinding finding : review.findings()) {
            insert("insert into review_issue(project_id, reviewed_commit_id, assignee_id, severity, title, file_path, line_number, description, suggestion, assignment_reason, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)",
                    current.id(), commitId, assignment.userId(), finding.severity(), finding.title(), finding.filePath(), finding.lineNumber(),
                    finding.description(), finding.suggestion(), assignment.reason(), Timestamp.from(now), Timestamp.from(now));
        }
        for (ManualReviewFile file : commit.manualFiles()) {
            long fileId = insert("insert into manual_review_file(reviewed_commit_id, project_id, file_path, old_object_sha, new_object_sha, old_mode, new_mode, reason_code) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    commitId, current.id(), file.filePath(), file.oldObjectSha(), file.newObjectSha(), file.oldMode(), file.newMode(), file.reasonCode());
            insert("insert into review_issue(project_id, reviewed_commit_id, assignee_id, severity, title, file_path, line_number, description, suggestion, assignment_reason, issue_kind, manual_file_id, status, created_at, updated_at) values (?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?, 'MANUAL_REVIEW', ?, 'OPEN', ?, ?)",
                    current.id(), commitId, assignment.userId(), "커밋 변경 파일 수동 확인", file.filePath(),
                    file.reasonDescription() + " 불변 커밋과 첫 부모의 전체 파일 목록을 대조했습니다. 이 커밋은 AI 본문 검토를 수행하지 않았으며 모든 변경 경로를 수동 확인 대상으로 배정했습니다. AI가 결함을 발견했다는 의미는 아닙니다.",
                    "원본 커밋에서 이 파일의 변경과 관련 파일의 영향을 확인한 뒤 확인 결과·해결 또는 제외 사유를 기록하세요.",
                    assignment.reason(), fileId, Timestamp.from(now), Timestamp.from(now));
        }
        if (!commit.manualFiles().isEmpty()) {
            audit(null, "MANUAL_REVIEW_ASSIGNED", "REVIEWED_COMMIT", commitId,
                    "files=" + commit.manualFiles().size() + "; evidence=PINNED_TREES; assignee=" + assignment.userId(), now);
        }
        jdbc.update("update review_run set reviewed_commits = reviewed_commits + 1 where id = ? and status = 'RUNNING'", runId);
        return true;
    }

    private Assignment resolveAssignment(ReviewProject project, String authorLogin, String authorEmail) {
        // Only github.com has this globally scoped account namespace. A GitLab server may
        // have an unrelated account with the same username; it must use an origin mapping.
        if ("GITHUB".equals(project.provider()) && "github.com".equals(project.repositoryHost()) &&
                authorLogin != null && !authorLogin.isBlank()) {
            List<Long> accounts = jdbc.queryForList("select id from app_user where enabled = true and lower(git_username) = ?", Long.class,
                    authorLogin.strip().toLowerCase(Locale.ROOT));
            if (accounts.size() == 1) return new Assignment(accounts.getFirst(), "GITHUB_ACCOUNT", null);
        }
        if (authorEmail != null) {
            String origin = RepositoryOrigin.fromRepositoryUrl(project.repositoryUrl());
            List<Assignment> mappings = jdbc.query("select m.id, m.user_id from git_author_mapping m join app_user u on u.id = m.user_id " +
                            "where m.repository_origin = ? and m.author_email = ? and u.enabled = true",
                    (rs, row) -> new Assignment(rs.getLong("user_id"), "GIT_EMAIL_MAPPING", rs.getLong("id")), origin, authorEmail);
            if (mappings.size() == 1) return mappings.getFirst();
        }
        return new Assignment(project.ownerId(), "PROJECT_OWNER_FALLBACK", null);
    }

    private record Assignment(long userId, String reason, Long mappingId) { }

    /** The adapter supplies an explicit closed checkpoint, or null while a merge group is incomplete. */
    public void completeBatch(long runId, ReviewProject original, String checkpoint, Instant now) {
        ReviewProject current = project(original.id(), true);
        requireApproved(current);
        if (!Objects.equals(current.lastReviewedSha(), original.lastReviewedSha())) {
            throw new IllegalStateException("Review cursor changed during the run");
        }
        if (checkpoint != null) {
            if (!alreadyReviewed(current.id(), checkpoint)) throw new IllegalStateException("Unreviewed checkpoint");
            jdbc.update("update project set last_reviewed_sha = ?, updated_at = ? where id = ?", checkpoint, Timestamp.from(now), current.id());
        }
        finishRun(runId, true, now, null);
    }

    public void finishRun(long runId, boolean succeeded, Instant now, String error) {
        jdbc.update("update review_run set status = ?, finished_at = ?, error_message = ? where id = ? and status = 'RUNNING'",
                succeeded ? "SUCCEEDED" : "FAILED", Timestamp.from(now), error, runId);
        audit(null, succeeded ? "REVIEW_SUCCEEDED" : "REVIEW_FAILED", "REVIEW_RUN", runId, error == null ? "리뷰 배치 완료" : error, now);
    }

    public List<Map<String, Object>> runs(long projectId) {
        return runs(projectId, 0).rows();
    }

    public HistoryPage runs(long projectId, int page) {
        requireHistoryPage(page);
        return historyPage(jdbc.queryForList("select id, status, started_at, finished_at, reviewed_commits, error_message from review_run where project_id = ? order by id desc limit ? offset ?",
                projectId, HISTORY_PAGE_SIZE + 1, (long) page * HISTORY_PAGE_SIZE), page);
    }

    public List<Map<String, Object>> reviewedCommits(long projectId) {
        return reviewedCommits(projectId, 0).rows();
    }

    public HistoryPage reviewedCommits(long projectId, int page) {
        requireHistoryPage(page);
        return historyPage(jdbc.queryForList("select c.id, c.commit_sha, c.author_login, c.summary, c.coverage_type, c.coverage_details, c.reviewed_at, (select count(*) from review_issue i where i.reviewed_commit_id = c.id) as issue_count from reviewed_commit c where c.project_id = ? order by c.id desc limit ? offset ?",
                projectId, HISTORY_PAGE_SIZE + 1, (long) page * HISTORY_PAGE_SIZE), page);
    }

    private static void requireHistoryPage(int page) {
        if (page < 0 || page > MAX_HISTORY_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리뷰 기록 페이지가 올바르지 않습니다.");
    }

    private static HistoryPage historyPage(List<Map<String, Object>> rows, int page) {
        return new HistoryPage(List.copyOf(rows.subList(0, Math.min(rows.size(), HISTORY_PAGE_SIZE))), page,
                rows.size() > HISTORY_PAGE_SIZE && page < MAX_HISTORY_PAGE);
    }

    public record HistoryPage(List<Map<String, Object>> rows, int page, boolean hasNext) { }

    public Map<String, Object> dashboard(ReviewActor actor) {
        String filter = actor.admin() ? "1 = 1" : "owner_id = " + actor.id();
        return Map.of("projectCount", count("select count(*) from project where " + filter),
                "pendingCount", count("select count(*) from project where status = 'PENDING' and " + filter),
                "openIssueCount", count("select count(*) from review_issue where status = 'OPEN'" + (actor.admin() ? "" : " and assignee_id = " + actor.id())),
                "failedRunCount", count("select count(*) from project p where " + (actor.admin() ? "1 = 1" : "p.owner_id = " + actor.id()) + " and (select r.status from review_run r where r.project_id = p.id order by r.id desc limit 1) = 'FAILED'"));
    }

    public List<Map<String, Object>> recentProjects(ReviewActor actor) {
        return jdbc.queryForList("select p.id, p.name, p.status, p.last_reviewed_sha, (select r.status from review_run r where r.project_id = p.id order by r.id desc limit 1) as review_status from project p" +
                (actor.admin() ? "" : " where p.owner_id = " + actor.id()) + " order by p.updated_at desc, p.id desc limit 12");
    }

    private long count(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class)); }

    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private void audit(Long actorId, String action, String targetType, long targetId, String detail, Instant now) {
        jdbc.update("insert into audit_event(actor_id, action, target_type, target_id, detail, created_at) values (?, ?, ?, ?, ?, ?)",
                actorId, action, targetType, targetId, detail, Timestamp.from(now));
    }

    public static void requireApproved(ReviewProject project) {
        if (!"APPROVED".equals(project.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "승인된 프로젝트만 리뷰할 수 있습니다.");
    }
}
