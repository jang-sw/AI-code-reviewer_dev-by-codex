package com.aicreviewer.review;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static com.aicreviewer.review.ReviewRecoveryException.Failure.*;

/** Resets only a paused project's checkpoint; prior reviews and issues remain immutable here. */
@Service
public class ReviewRecoveryService {
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final AuditEventWriter audit;
    private final ProjectReviewLock locks;
    private final TransactionTemplate transactions;

    public ReviewRecoveryService(JdbcTemplate jdbc, UserAccountService users, AuditEventWriter audit,
                                 ProjectReviewLock locks, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.locks = locks;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void resetProgress(String username, long projectId, String repositoryUrl, String expectedCursor, String reason) {
        users.requireAdmin(username);
        String explanation = validateInput(projectId, repositoryUrl, expectedCursor, reason);
        // The lock order is always advisory lease -> project row. An outer transaction may
        // already own that row and must not outlive this lease or block this fresh transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Review recovery must start outside an existing transaction");
        }
        var lease = locks.tryAcquire(projectId).orElseThrow(() -> new ReviewRecoveryException(BUSY));
        try (lease) {
            // executeWithoutResult commits (or rolls back) before returning; close the lease afterward.
            transactions.executeWithoutResult(transaction -> {
                var actor = users.requireAdmin(username);
                var projects = jdbc.query("SELECT repository_url, status, last_reviewed_sha FROM project WHERE id = ? FOR UPDATE",
                        (rs, row) -> new RecoveryProject(rs.getString("repository_url"), rs.getString("status"), rs.getString("last_reviewed_sha")), projectId);
                if (projects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
                var project = projects.getFirst();
                if (!"PAUSED".equals(project.status())) throw new ReviewRecoveryException(NOT_PAUSED);
                if (project.cursor() == null || !project.cursor().equals(expectedCursor)) {
                    throw new ReviewRecoveryException(STALE_CURSOR);
                }
                if (!project.repositoryUrl().equals(repositoryUrl)) {
                    throw new ReviewRecoveryException(REPOSITORY_MISMATCH);
                }
                jdbc.update("UPDATE project SET last_reviewed_sha = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?", projectId);
                audit.write(actor.id(), "PROJECT_REVIEW_PROGRESS_RESET", "PROJECT", projectId,
                        "oldSha=" + project.cursor() + "; reason=" + explanation);
            });
        }
    }

    private static String validateInput(long projectId, String repositoryUrl, String expectedCursor, String reason) {
        if (projectId < 1 || repositoryUrl == null || repositoryUrl.isBlank() || repositoryUrl.length() > 2048
                || repositoryUrl.codePoints().anyMatch(Character::isISOControl)
                || expectedCursor == null || !expectedCursor.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new ReviewRecoveryException(INVALID_CONFIRMATION);
        }
        if (reason == null || reason.length() > 500 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ReviewRecoveryException(INVALID_REASON);
        }
        String explanation = reason.strip();
        if (explanation.codePointCount(0, explanation.length()) < 5) {
            throw new ReviewRecoveryException(INVALID_REASON);
        }
        return explanation;
    }

    private record RecoveryProject(String repositoryUrl, String status, String cursor) { }
}
