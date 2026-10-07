package com.aicreviewer.project;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ProjectReviewLock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

import static com.aicreviewer.project.BranchCorrectionException.Failure.*;

@Service
public class BranchCorrectionService {
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final AuditEventWriter audit;
    private final ProjectReviewLock locks;
    private final TransactionTemplate transactions;

    public BranchCorrectionService(JdbcTemplate jdbc, UserAccountService users, AuditEventWriter audit,
                                   ProjectReviewLock locks, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.locks = locks;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void correct(String username, long projectId, String expectedBranch, String expectedCursor,
                        String newBranch, String reason, boolean confirmed) {
        users.requireAdmin(username);
        if (projectId < 1 || !confirmed || expectedBranch == null || expectedBranch.length() > 255
                || expectedBranch.codePoints().anyMatch(Character::isISOControl) || expectedCursor == null
                || (!expectedCursor.isEmpty() && !expectedCursor.matches("[0-9a-f]{40}|[0-9a-f]{64}"))) {
            throw new BranchCorrectionException(INVALID_CONFIRMATION);
        }
        String branch;
        try { branch = ProjectService.validateBranch(newBranch); }
        catch (IllegalArgumentException invalid) { throw new BranchCorrectionException(INVALID_BRANCH); }
        if (reason == null || reason.length() > 500 || reason.codePoints().anyMatch(Character::isISOControl)
                || reason.strip().codePointCount(0, reason.strip().length()) < 5) {
            throw new BranchCorrectionException(INVALID_REASON);
        }
        String explanation = reason.strip();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Branch correction must start outside an existing transaction");
        }
        var lease = locks.tryAcquire(projectId).orElseThrow(() -> new BranchCorrectionException(BUSY));
        try (lease) {
            // Commit/rollback completes before closing the dedicated session lease.
            // Keep the worker's advisory -> project -> request lock order.
            transactions.executeWithoutResult(transaction -> {
                users.requireAdmin(username);
                var projects = jdbc.query("SELECT status,review_branch,last_reviewed_sha FROM project WHERE id=? FOR UPDATE",
                        (rs, row) -> new Snapshot(rs.getString("status"), rs.getString("review_branch"), rs.getString("last_reviewed_sha")), projectId);
                if (projects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                var project = projects.getFirst();
                var states = jdbc.queryForList("SELECT state FROM review_request WHERE project_id=? FOR UPDATE", String.class, projectId);
                if (states.stream().anyMatch(state -> "QUEUED".equals(state) || "RUNNING".equals(state))) {
                    throw new BranchCorrectionException(ACTIVE_REQUEST);
                }
                boolean beforeExecution = ("PENDING".equals(project.status()) || "REJECTED".equals(project.status()))
                        && project.cursor() == null
                        && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM review_run WHERE project_id=?) " +
                        "OR EXISTS(SELECT 1 FROM reviewed_commit WHERE project_id=?)", Boolean.class, projectId, projectId));
                if (!"PAUSED".equals(project.status()) && !beforeExecution) throw new BranchCorrectionException(INVALID_STATE);
                if (!Objects.equals(project.branch(), nullable(expectedBranch)) || !Objects.equals(project.cursor(), nullable(expectedCursor))) {
                    throw new BranchCorrectionException(STALE);
                }
                if (Objects.equals(project.branch(), branch)) throw new BranchCorrectionException(UNCHANGED);
                // Row-lock waits may outlive an administrator's authorization.
                var actor = users.requireAdmin(username);
                jdbc.update("UPDATE project SET review_branch=?, last_reviewed_sha=NULL, next_review_at=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        branch, projectId);
                audit.write(actor.id(), "PROJECT_REVIEW_BRANCH_CORRECTED", "PROJECT", projectId,
                        "oldBranch=" + display(project.branch()) + "; newBranch=" + display(branch)
                                + "; oldSha=" + display(project.cursor()) + "; reason=" + explanation);
            });
        }
    }

    private static String nullable(String value) { return value.isEmpty() ? null : value; }
    private static String display(String value) { return value == null ? "(none)" : value; }
    private record Snapshot(String status, String branch, String cursor) { }
}
