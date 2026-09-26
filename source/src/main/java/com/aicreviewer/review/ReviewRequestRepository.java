package com.aicreviewer.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One durable, coalesced request per project. All ownership changes hold the project row first. */
@Repository
public class ReviewRequestRepository {
    public static final int MAX_CANDIDATES = 1016;
    public static final int RETRY_SECONDS = 30;
    private static final RowMapper<Request> REQUEST = (rs, row) -> new Request(rs.getLong("project_id"),
            rs.getString("request_id"), rs.getString("state"), rs.getString("source"),
            rs.getObject("requested_by", Long.class), rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("available_at").toInstant(), instant(rs.getTimestamp("last_attempt_at")),
            rs.getInt("attempt_count"), rs.getObject("run_id", Long.class), instant(rs.getTimestamp("finished_at")),
            rs.getString("result_code"));
    private final JdbcTemplate jdbc;
    private final ReviewRepository reviews;
    private final TransactionTemplate transactions;

    public ReviewRequestRepository(JdbcTemplate jdbc, ReviewRepository reviews, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reviews = reviews;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public EnqueueResult enqueueManual(long projectId, String username, Instant now) {
        outsideTransaction();
        return transactions.execute(status -> {
            ReviewProject project = reviews.project(projectId, true);
            ReviewActor actor = reviews.actor(username);
            if (!actor.admin() && actor.id() != project.ownerId()) {
                throw new org.springframework.security.access.AccessDeniedException("프로젝트 접근 권한이 없습니다.");
            }
            ReviewRepository.requireApproved(project);
            if (ineligible(project, actor.id()) != null) {
                throw new org.springframework.security.access.AccessDeniedException("승인된 활성 계정이 필요합니다.");
            }
            return enqueue(projectId, "MANUAL", actor.id(), now);
        });
    }

    /** An active request absorbs this schedule tick; the next due time advances in the same transaction. */
    public EnqueueResult enqueueScheduled(long projectId, Instant dueBefore, Instant nextReviewAt) {
        outsideTransaction();
        if (dueBefore == null || nextReviewAt == null || !nextReviewAt.isAfter(dueBefore)) {
            throw new IllegalArgumentException("The next review schedule must be in the future");
        }
        return transactions.execute(status -> {
            ReviewProject project = reviews.project(projectId, true);
            if (!"APPROVED".equals(project.status())) return EnqueueResult.SKIPPED;
            Timestamp due = jdbc.queryForObject("select next_review_at from project where id = ?", Timestamp.class, projectId);
            if (due != null && due.toInstant().isAfter(dueBefore)) return EnqueueResult.SKIPPED;
            EnqueueResult result = enqueue(projectId, "SCHEDULED", null, dueBefore);
            jdbc.update("update project set next_review_at = ? where id = ?", Timestamp.from(nextReviewAt), projectId);
            return result;
        });
    }

    private EnqueueResult enqueue(long projectId, String source, Long actorId, Instant now) {
        Objects.requireNonNull(now, "Request time is required");
        Optional<Request> existing = lockedRequest(projectId);
        if (existing.isPresent() && existing.get().active()) return EnqueueResult.ALREADY_QUEUED;
        String requestId = UUID.randomUUID().toString();
        if (existing.isEmpty()) {
            jdbc.update("insert into review_request(project_id, request_id, state, source, requested_by, requested_at, available_at) values (?, ?, 'QUEUED', ?, ?, ?, ?)",
                    projectId, requestId, source, actorId, Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("update review_request set request_id = ?, claim_token = null, state = 'QUEUED', source = ?, requested_by = ?, requested_at = ?, available_at = ?, last_attempt_at = null, attempt_count = 0, run_id = null, finished_at = null, result_code = null where project_id = ?",
                    requestId, source, actorId, Timestamp.from(now), Timestamp.from(now), projectId);
        }
        audit(actorId, "REVIEW_REQUESTED", projectId, "source=" + source + "; request=" + requestId, now);
        return EnqueueResult.QUEUED;
    }

    public List<Long> scheduledCandidates(Instant now, int limit) {
        requireLimit(limit);
        // Each disjoint range follows the existing (status, next_review_at, id)
        // index. Only their bounded results need the global NULLS FIRST ordering.
        return jdbc.query("""
                (select id, next_review_at from project where status = 'APPROVED' and next_review_at is null
                 order by id limit ?)
                union all
                (select id, next_review_at from project where status = 'APPROVED' and next_review_at <= ?
                 order by next_review_at, id limit ?)
                order by next_review_at asc nulls first, id limit ?
                """, (rs, row) -> rs.getLong("id"), limit, Timestamp.from(now), limit, limit);
    }

    public List<Request> candidates(Instant now, int limit) {
        requireLimit(limit);
        // Read each state through its ordered index before merging. IN (...) followed
        // by one global sort would otherwise scan all active requests on every poll.
        return jdbc.query("""
                (select * from review_request where state = 'QUEUED' and available_at <= ?
                 order by available_at, requested_at, project_id limit ?)
                union all
                (select * from review_request where state = 'RUNNING' and available_at <= ?
                 order by available_at, requested_at, project_id limit ?)
                order by available_at, requested_at, project_id limit ?
                """, REQUEST, Timestamp.from(now), limit, Timestamp.from(now), limit, limit);
    }

    public Optional<Request> find(long projectId) {
        return jdbc.query("select * from review_request where project_id = ?", REQUEST, projectId).stream().findFirst();
    }

    public boolean isActive(long projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from review_request where project_id = ? and state in ('QUEUED', 'RUNNING'))", Boolean.class, projectId));
    }

    public void deferBusy(Request request, Instant availableAt) {
        // This is a polling hint, never an ownership claim or an execution timeout.
        jdbc.update("update review_request set available_at = ? where project_id = ? and request_id = ? and state in ('QUEUED', 'RUNNING') and available_at < ?",
                Timestamp.from(availableAt), request.projectId(), request.requestId(), Timestamp.from(availableAt));
    }

    /** Caller must already hold the dedicated project advisory lease, and have no ambient transaction. */
    public Claim claim(Request snapshot, Instant now) {
        outsideTransaction();
        return transactions.execute(status -> {
            ReviewProject project = reviews.project(snapshot.projectId(), true);
            Request request = lockedRequest(snapshot.projectId()).orElse(null);
            if (request == null || !request.requestId().equals(snapshot.requestId()) || !request.active()) return null;
            String ineligible = ineligible(project, request.requestedBy());
            if (ineligible != null) {
                cancelLocked(request, ineligible, now);
                return null;
            }
            String token = UUID.randomUUID().toString();
            long runId = reviews.startRun(project.id(), request.requestedBy(), now);
            jdbc.update("update review_request set state = 'RUNNING', claim_token = ?, run_id = ?, last_attempt_at = ?, available_at = ?, attempt_count = attempt_count + 1, finished_at = null, result_code = null where project_id = ?",
                    token, runId, Timestamp.from(now), Timestamp.from(now.plusSeconds(recoveryDelay(request.attemptCount()))), project.id());
            return new Claim(project.id(), request.requestId(), token, runId, request.requestedBy());
        });
    }

    /** This lock and every corresponding write must share the caller's transaction. */
    public ReviewProject guard(Claim claim) {
        requireTransaction();
        ReviewProject project = reviews.project(claim.projectId(), true);
        Request request = requireCurrent(claim);
        if (ineligible(project, request.requestedBy()) != null) throw new RequestCancelledException();
        return project;
    }

    public void complete(Claim claim, ReviewProject original, String checkpoint, Instant now) {
        guard(claim);
        reviews.completeBatch(claim.runId(), original, checkpoint, now);
        jdbc.update("update review_request set state = 'SUCCEEDED', finished_at = ?, result_code = 'BATCH_COMPLETED' where project_id = ?",
                Timestamp.from(now), claim.projectId());
    }

    /** Returns false for an obsolete worker; it must never change its replacement's run or request. */
    public boolean fail(Claim claim, String safeError, Instant now) {
        requireTransaction();
        ReviewProject project = reviews.project(claim.projectId(), true);
        final Request request;
        try { request = requireCurrent(claim); }
        catch (StaleClaimException obsolete) { return false; }
        String ineligible = ineligible(project, request.requestedBy());
        reviews.finishRun(claim.runId(), false, now, safeError);
        jdbc.update("update review_request set state = ?, finished_at = ?, result_code = ? where project_id = ?",
                ineligible == null ? "FAILED" : "CANCELLED", Timestamp.from(now),
                ineligible == null ? "REVIEW_FAILED" : ineligible, claim.projectId());
        return true;
    }

    private Request requireCurrent(Claim claim) {
        Request request = lockedRequest(claim.projectId()).orElseThrow(StaleClaimException::new);
        String token = jdbc.queryForObject("select claim_token from review_request where project_id = ?", String.class, claim.projectId());
        if (!request.requestId().equals(claim.requestId()) || !"RUNNING".equals(request.state())
                || !Objects.equals(request.runId(), claim.runId()) || !Objects.equals(token, claim.claimToken())) {
            throw new StaleClaimException();
        }
        String state = jdbc.queryForObject("select status from review_run where id = ? and project_id = ?", String.class, claim.runId(), claim.projectId());
        if (!"RUNNING".equals(state)) throw new StaleClaimException();
        return request;
    }

    private String ineligible(ReviewProject project, Long actorId) {
        if (!"APPROVED".equals(project.status())) return "PROJECT_INELIGIBLE";
        if (actorId == null) return null;
        // Read the current account, never a serialized principal or the username from queueing time.
        List<String> roles = jdbc.queryForList("select role from app_user where id = ? and enabled = true and approval_status = 'APPROVED'", String.class, actorId);
        return roles.size() == 1 && ("ADMIN".equals(roles.getFirst()) || project.ownerId() == actorId)
                ? null : "REQUESTER_INELIGIBLE";
    }

    private void cancelLocked(Request request, String code, Instant now) {
        if (request.runId() != null && "RUNNING".equals(request.state())) {
            reviews.finishRun(request.runId(), false, now, "리뷰 요청의 프로젝트 상태 또는 요청자 권한이 변경되어 취소했습니다.");
        }
        jdbc.update("update review_request set state = 'CANCELLED', finished_at = ?, result_code = ? where project_id = ?",
                Timestamp.from(now), code, request.projectId());
        audit(request.requestedBy(), "REVIEW_REQUEST_CANCELLED", request.projectId(), "reason=" + code, now);
    }

    private Optional<Request> lockedRequest(long projectId) {
        return jdbc.query("select * from review_request where project_id = ? for update", REQUEST, projectId).stream().findFirst();
    }

    private void audit(Long actorId, String action, long projectId, String detail, Instant now) {
        jdbc.update("insert into audit_event(actor_id, action, target_type, target_id, detail, created_at) values (?, ?, 'PROJECT', ?, ?, ?)",
                actorId, action, projectId, detail, Timestamp.from(now));
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static long recoveryDelay(int previousAttempts) {
        // A polling backoff, never a lease expiry: ownership still requires the project advisory lock.
        return Math.min(3600L, RETRY_SECONDS * (1L << Math.min(previousAttempts, 7)));
    }
    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_CANDIDATES) throw new IllegalArgumentException("Review request candidate limit is invalid");
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Review ownership validation requires a transaction");
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Review request operation must start outside an existing transaction");
    }

    public enum EnqueueResult { QUEUED, ALREADY_QUEUED, SKIPPED }
    public record Request(long projectId, String requestId, String state, String source, Long requestedBy,
                          Instant requestedAt, Instant availableAt, Instant lastAttemptAt, int attemptCount,
                          Long runId, Instant finishedAt, String resultCode) {
        public boolean active() { return "QUEUED".equals(state) || "RUNNING".equals(state); }
    }
    public record Claim(long projectId, String requestId, String claimToken, long runId, Long requestedBy) { }
    public static final class StaleClaimException extends RuntimeException {
        public StaleClaimException() { super("Review request ownership has changed"); }
    }
    public static final class RequestCancelledException extends RuntimeException {
        public RequestCancelledException() { super("Review request authorization or project status has changed"); }
    }
}
