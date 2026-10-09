package com.aicreviewer.issue;

import com.aicreviewer.git.GitCommitLink;
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
        validateFilter(status, page);
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
        args.add(PAGE_SIZE + 1);
        args.add(page * PAGE_SIZE);
        // Select the scoped page before fetching issue text and related records. Deep offsets
        // still scan IDs, but only the page plus lookahead needs these joins and provenance checks.
        List<Map<String, Object>> issues = jdbc.queryForList("select i.id, i.issue_kind, i.severity, i.status, i.title, i.file_path, i.line_number, i.assignment_reason, left(i.description, 240) as description_preview, p.name as project_name, p.repository_url, u.username as assignee_username, c.commit_sha, c.author_login, " +
                "exists(select 1 from audit_event a where a.action = 'ISSUE_ASSIGNEE_FALLBACK' and a.target_type = 'REVIEWED_COMMIT' and a.target_id = c.id) as fallback_assignment " +
                "from (select i.id from review_issue i" + filter + " order by i.id desc limit ? offset ?) selected " +
                "join review_issue i on i.id = selected.id join project p on p.id = i.project_id join app_user u on u.id = i.assignee_id join reviewed_commit c on c.id = i.reviewed_commit_id " +
                "order by i.id desc", args.toArray());
        for (var issue : issues) {
            issue.put("commit_url", GitCommitLink.from((String) issue.remove("repository_url"), (String) issue.get("commit_sha")));
        }
        boolean hasNext = issues.size() > PAGE_SIZE;
        return new IssuePage(List.copyOf(issues.subList(0, Math.min(issues.size(), PAGE_SIZE))), page, hasNext);
    }

    public Map<String, Object> detail(long id, ReviewActor actor) {
        var args = new ArrayList<Object>();
        args.add(id);
        String scope = " where i.id = ?";
        if (!actor.admin()) {
            scope += " and i.assignee_id = ?";
            args.add(actor.id());
        }
        var issues = jdbc.queryForList("select i.*, p.name as project_name, p.repository_url, u.username as assignee_username, c.commit_sha, c.author_login, " +
                "m.old_object_sha as manual_old_object_sha, m.new_object_sha as manual_new_object_sha, m.old_mode as manual_old_mode, m.new_mode as manual_new_mode, m.reason_code as manual_reason_code, m.evidence_kind as manual_evidence_kind, " +
                "exists(select 1 from audit_event a where a.action = 'ISSUE_ASSIGNEE_FALLBACK' and a.target_type = 'REVIEWED_COMMIT' and a.target_id = c.id) as fallback_assignment " +
                "from review_issue i join project p on p.id = i.project_id join app_user u on u.id = i.assignee_id join reviewed_commit c on c.id = i.reviewed_commit_id " +
                "left join manual_review_file m on m.id = i.manual_file_id and m.project_id = i.project_id and m.reviewed_commit_id = i.reviewed_commit_id" + scope, args.toArray());
        if (issues.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다.");
        var issue = issues.getFirst();
        issue.put("commit_url", GitCommitLink.from((String) issue.remove("repository_url"), (String) issue.get("commit_sha")));
        if ("MANUAL_REVIEW".equals(issue.get("issue_kind"))) {
            issue.put("manual_reason_label", switch (String.valueOf(issue.get("manual_reason_code"))) {
                case "SOURCE_DIFF_UNAVAILABLE" -> "Git 서비스에서 AI 검토에 필요한 전체 변경 내용을 확보하지 못했습니다.";
                case "GIT_DIFF_BUDGET" -> "변경 내용이 수집 범위 또는 크기 제한을 초과했습니다.";
                case "AI_INPUT_LIMIT" -> "변경 내용이 AI 입력 크기 제한을 초과했습니다.";
                case "METADATA_CHANGE" -> "경로·권한·파일 유형 또는 검증된 빈 파일의 생성·삭제를 직접 확인해야 합니다.";
                default -> "AI 검토를 완료하지 못해 직접 확인이 필요합니다.";
            });
        }
        return issue;
    }

    static void validateFilter(String status, int page) {
        if (page < 0 || page > 10000 || (status != null && !status.isEmpty() && !STATUSES.contains(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이슈 조회 조건이 올바르지 않습니다.");
        }
    }

    @Transactional
    public void changeStatus(long issueId, String nextStatus, ReviewActor actor) {
        changeStatus(issueId, nextStatus, actor, "");
    }

    @Transactional
    public void changeStatus(long issueId, String nextStatus, ReviewActor actor, String reason) {
        if (nextStatus == null || !STATUSES.contains(nextStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 이슈 상태가 필요합니다.");
        var rows = actor.admin() ?
                jdbc.queryForList("select status, issue_kind, resolution_note from review_issue where id = ? for update", issueId) :
                jdbc.queryForList("select status, issue_kind, resolution_note from review_issue where id = ? and assignee_id = ? for update", issueId, actor.id());
        // An unrelated user cannot discover whether this issue exists.
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이슈를 찾을 수 없습니다.");
        var issue = rows.getFirst();
        String previous = (String) issue.get("status");
        boolean manual = "MANUAL_REVIEW".equals(issue.get("issue_kind"));
        String note = manual ? validateReason(reason) : "";
        if (previous.equals(nextStatus) && (!manual || note.equals(issue.get("resolution_note")))) return;
        Timestamp now = Timestamp.from(Instant.now());
        if (manual) {
            jdbc.update("update review_issue set status = ?, resolution_note = ?, updated_at = ? where id = ?", nextStatus, note, now, issueId);
        } else {
            jdbc.update("update review_issue set status = ?, updated_at = ? where id = ?", nextStatus, now, issueId);
        }
        String action = manual && previous.equals(nextStatus) ? "MANUAL_REVIEW_NOTE_UPDATED" : "ISSUE_STATUS_CHANGED";
        String detail = previous + " -> " + nextStatus + (manual ? "; reason=" + note : "");
        jdbc.update("insert into audit_event(actor_id, action, target_type, target_id, detail, created_at) values (?, ?, 'REVIEW_ISSUE', ?, ?, ?)",
                actor.id(), action, issueId, detail, now);
    }

    private static String validateReason(String reason) {
        if (reason == null || reason.length() > 1000 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ManualIssueReasonException();
        }
        String note = reason.strip();
        if (note.codePointCount(0, note.length()) < 5) {
            throw new ManualIssueReasonException();
        }
        return note;
    }

    public record IssuePage(List<Map<String, Object>> issues, int page, boolean hasNext) { }
}
