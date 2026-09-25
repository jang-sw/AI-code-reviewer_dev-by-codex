package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class IssueService {
    public static final int PAGE_SIZE = 25;
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "DISMISSED");
    private final JdbcTemplate jdbc;

    public IssueService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public IssuePage list(ReviewActor actor, String status, int page) {
        if (page < 0 || page > 10000 || (status != null && !status.isBlank() && !STATUSES.contains(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이슈 조회 조건이 올바르지 않습니다.");
        }
        var args = new ArrayList<Object>();
        String filter = " where 1 = 1";
        if (!actor.admin()) {
            filter += " and i.assignee_id = ?";
            args.add(actor.id());
        }
        if (status != null && !status.isBlank()) {
            filter += " and i.status = ?";
            args.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from review_issue i" + filter, Long.class, args.toArray());
        args.add(PAGE_SIZE);
        args.add(page * PAGE_SIZE);
        List<Map<String, Object>> issues = jdbc.queryForList("select i.*, p.name as project_name, u.username as assignee_username, c.commit_sha, c.author_login, " +
                "exists(select 1 from audit_event a where a.action = 'ISSUE_ASSIGNEE_FALLBACK' and a.target_type = 'REVIEWED_COMMIT' and a.target_id = c.id) as fallback_assignment " +
                "from review_issue i join project p on p.id = i.project_id join app_user u on u.id = i.assignee_id join reviewed_commit c on c.id = i.reviewed_commit_id" +
                filter + " order by i.id desc limit ? offset ?", args.toArray());
        return new IssuePage(issues, total == null ? 0 : total, page);
    }

    @Transactional
    public void changeStatus(long issueId, String nextStatus, ReviewActor actor) {
        if (nextStatus == null || !STATUSES.contains(nextStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 이슈 상태가 필요합니다.");
        var statuses = actor.admin() ?
                jdbc.queryForList("select status from review_issue where id = ? for update", String.class, issueId) :
                jdbc.queryForList("select status from review_issue where id = ? and assignee_id = ? for update", String.class, issueId, actor.id());
        // An unrelated user cannot discover whether this issue exists.
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다.");
        String previous = statuses.getFirst();
        if (previous.equals(nextStatus)) return;
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update review_issue set status = ?, updated_at = ? where id = ?", nextStatus, now, issueId);
        jdbc.update("insert into audit_event(actor_id, action, target_type, target_id, detail, created_at) values (?, 'ISSUE_STATUS_CHANGED', 'REVIEW_ISSUE', ?, ?, ?)",
                actor.id(), issueId, previous + " -> " + nextStatus, now);
    }

    public record IssuePage(List<Map<String, Object>> issues, long total, int page) {
        public boolean hasNext() { return (long) (page + 1) * PAGE_SIZE < total; }
    }
}
