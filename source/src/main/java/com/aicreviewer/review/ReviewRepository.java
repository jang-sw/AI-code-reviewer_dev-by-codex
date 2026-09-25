package com.aicreviewer.review;

import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Repository
public class ReviewRepository {
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

    public List<Long> approvedProjectIds() {
        return jdbc.queryForList("select p.id from project p where p.status = 'APPROVED' order by (select max(r.started_at) from review_run r where r.project_id = p.id) asc nulls first, p.id", Long.class);
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

    /** Caller wraps this entire operation in one database transaction. */
    public boolean persistCommit(long runId, ReviewProject original, String expectedCursor,
                                 GitCommit commit, ReviewResult review, Instant now) {
        ReviewProject current = project(original.id(), true);
        requireApproved(current);
        if (!Objects.equals(current.lastReviewedSha(), expectedCursor)) {
            throw new IllegalStateException("Review cursor changed during the run");
        }
        if (alreadyReviewed(current.id(), commit.sha())) return false;
        long commitId = insert("insert into reviewed_commit(project_id, commit_sha, author_login, summary, reviewed_at) values (?, ?, ?, ?, ?)",
                current.id(), commit.sha(), commit.authorLogin(), review.summary(), Timestamp.from(now));
        List<Long> candidates = commit.authorLogin() == null || commit.authorLogin().isBlank() ? List.of() :
                jdbc.queryForList("select id from app_user where enabled = true and lower(git_username) = ?", Long.class,
                        commit.authorLogin().strip().toLowerCase(Locale.ROOT));
        long assignee = candidates.size() == 1 ? candidates.getFirst() : current.ownerId();
        if (candidates.size() != 1 && !review.findings().isEmpty()) {
            audit(null, "ISSUE_ASSIGNEE_FALLBACK", "REVIEWED_COMMIT", commitId,
                    "Git 작성자와 일치하는 활성 계정이 없어 프로젝트 소유자에게 배정했습니다. assignee=" + assignee, now);
        }
        for (ReviewFinding finding : review.findings()) {
            insert("insert into review_issue(project_id, reviewed_commit_id, assignee_id, severity, title, file_path, line_number, description, suggestion, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)",
                    current.id(), commitId, assignee, finding.severity(), finding.title(), finding.filePath(), finding.lineNumber(),
                    finding.description(), finding.suggestion(), Timestamp.from(now), Timestamp.from(now));
        }
        jdbc.update("update review_run set reviewed_commits = reviewed_commits + 1 where id = ? and status = 'RUNNING'", runId);
        return true;
    }

    /** The Git adapter returns only batches ending at a safe first-parent boundary. */
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
        return jdbc.queryForList("select id, status, started_at, finished_at, reviewed_commits, error_message from review_run where project_id = ? order by id desc limit 50", projectId);
    }

    public List<Map<String, Object>> reviewedCommits(long projectId) {
        return jdbc.queryForList("select c.id, c.commit_sha, c.author_login, c.summary, c.reviewed_at, (select count(*) from review_issue i where i.reviewed_commit_id = c.id) as issue_count from reviewed_commit c where c.project_id = ? order by c.id desc limit 50", projectId);
    }

    public Map<String, Object> dashboard(ReviewActor actor) {
        String filter = actor.admin() ? "1 = 1" : "owner_id = " + actor.id();
        return Map.of("projectCount", count("select count(*) from project where " + filter),
                "pendingCount", count("select count(*) from project where status = 'PENDING' and " + filter),
                "openIssueCount", count("select count(*) from review_issue where status = 'OPEN'" + (actor.admin() ? "" : " and assignee_id = " + actor.id())),
                "failedRunCount", count("select count(*) from project p where " + (actor.admin() ? "1 = 1" : "p.owner_id = " + actor.id()) + " and (select r.status from review_run r where r.project_id = p.id order by r.id desc limit 1) = 'FAILED'"));
    }

    public List<Map<String, Object>> recentProjects(ReviewActor actor) {
        return jdbc.queryForList("select p.id, p.name, p.status, p.last_reviewed_sha, (select r.status from review_run r where r.project_id = p.id order by r.id desc limit 1) as review_status from project p" +
                (actor.admin() ? "" : " where p.owner_id = " + actor.id()) + " order by p.updated_at desc limit 12");
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
