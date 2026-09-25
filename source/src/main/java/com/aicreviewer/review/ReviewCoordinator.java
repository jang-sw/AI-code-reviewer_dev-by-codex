package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReviewCoordinator {
    private static final System.Logger LOG = System.getLogger(ReviewCoordinator.class.getName());
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> COVERAGE_TYPES = Set.of("FULL", "EMPTY", "METADATA_ONLY");
    private final ReviewRepository repository;
    private final ProjectReviewLock locks;
    private final GitRepositoryClient git;
    private final AiReviewClient ai;
    private final TransactionTemplate transactions;
    private final int maxCommits;

    public ReviewCoordinator(ReviewRepository repository, ProjectReviewLock locks, GitRepositoryClient git,
                             AiReviewClient ai, PlatformTransactionManager transactionManager,
                             @Value("${app.review.max-commits:100}") int maxCommits) {
        if (maxCommits < 1 || maxCommits > 1000) throw new IllegalArgumentException("Review batch size must be between 1 and 1000");
        this.repository = repository;
        this.locks = locks;
        this.git = git;
        this.ai = ai;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxCommits = maxCommits;
    }

    public void authorizeManual(long projectId, String username) {
        ReviewRepository.requireApproved(repository.authorizedProject(projectId, repository.actor(username)));
    }

    public List<Long> scheduledProjects(int limit) { return repository.approvedProjectIds(limit); }

    /** Null username identifies the trusted scheduler; web callers always supply a principal. */
    public Outcome reviewProject(long projectId, String username) {
        ReviewActor actor = username == null ? null : repository.actor(username);
        ReviewProject initial = actor == null ? repository.project(projectId, false) : repository.authorizedProject(projectId, actor);
        ReviewRepository.requireApproved(initial);
        var lease = locks.tryAcquire(projectId);
        if (lease.isEmpty()) return Outcome.BUSY;
        try (var heldLock = lease.get()) {
            ReviewProject project = actor == null ? repository.project(projectId, false) : repository.authorizedProject(projectId, repository.actor(username));
            ReviewRepository.requireApproved(project);
            Long runId = transactions.execute(status -> repository.startRun(projectId, actor == null ? null : actor.id(), Instant.now()));
            if (runId == null) throw new IllegalStateException("No review run was created");
            try {
                Set<String> reviewedShas = repository.reviewedShas(projectId);
                GitReviewBatch batch = git.batch(project.repository(), project.branch(), project.lastReviewedSha(), reviewedShas, maxCommits);
                validateBatch(batch, reviewedShas);
                List<GitCommit> commits = batch.commits();
                for (GitCommit commit : commits) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Review interrupted");
                    // Retries never create a second issue set or move a cursor backwards.
                    if (repository.alreadyReviewed(projectId, commit.sha())) continue;
                    ReviewResult result = reviewContent(commit);
                    validateReview(result);
                    transactions.execute(status -> repository.persistCommit(runId, project, project.lastReviewedSha(), commit, result, Instant.now()));
                }
                // A partial merge can persist progress without a checkpoint. Conversely, a
                // fully persisted retry can advance to a safe checkpoint with no new commits.
                transactions.executeWithoutResult(status -> repository.completeBatch(runId, project, batch.checkpointSha(), Instant.now()));
                return Outcome.SUCCEEDED;
            } catch (RuntimeException exception) {
                // IntegrationException has an explicit safe-message contract. Other exception
                // messages may include source code or credentials and must never reach persistence.
                String reason = exception.getClass().getSimpleName();
                if (exception instanceof IntegrationException && exception.getMessage() != null) {
                    reason = exception.getMessage().replace('\n', ' ').replace('\r', ' ');
                    if (reason.length() > 500) reason = reason.substring(0, 500);
                }
                String safeError = "리뷰 처리 실패: " + reason + ". 저장된 커밋 리뷰를 재사용하여 재시도할 수 있습니다.";
                transactions.executeWithoutResult(status -> repository.finishRun(runId, false, Instant.now(), safeError));
                LOG.log(System.Logger.Level.WARNING, "Review run {0} for project {1} failed: {2}", runId, projectId, exception.getClass().getSimpleName());
                return Outcome.FAILED;
            }
        }
    }

    private ReviewResult reviewContent(GitCommit commit) {
        return switch (commit.coverageType()) {
            case "EMPTY" -> new ReviewResult("AI 본문 검토 없음: Git 저장소에서 파일 변경이 없는 커밋임을 확인했습니다.", List.of());
            case "METADATA_ONLY" -> new ReviewResult("AI 본문 검토 없음: 동일한 파일 본문의 경로·파일 모드 변경입니다. 경로·권한·파일 유형 변경 수동 확인 필요.", List.of());
            case "FULL" -> ai.review(commit);
            default -> throw new IllegalArgumentException("Invalid review coverage type");
        };
    }

    private void validateBatch(GitReviewBatch batch, Set<String> reviewedShas) {
        if (batch == null || batch.commits() == null || batch.commits().size() > maxCommits) {
            throw new IllegalArgumentException("Invalid commit batch");
        }
        Set<String> seen = new HashSet<>();
        for (GitCommit commit : batch.commits()) {
            if (commit == null || commit.sha() == null || !commit.sha().matches("[0-9a-f]{40}|[0-9a-f]{64}") || !seen.add(commit.sha()) ||
                    commit.message() == null || commit.diff() == null || (commit.authorLogin() != null && commit.authorLogin().length() > 100) ||
                    (commit.authorEmail() != null && (commit.authorEmail().length() > 320 || commit.authorEmail().chars().anyMatch(Character::isISOControl))) ||
                    commit.coverageType() == null || !COVERAGE_TYPES.contains(commit.coverageType()) ||
                    commit.coverageDetails() == null || commit.coverageDetails().length() > 16000 ||
                    ("EMPTY".equals(commit.coverageType()) && !commit.diff().isBlank()) ||
                    ("METADATA_ONLY".equals(commit.coverageType()) && (commit.coverageDetails().isBlank() || containsPatchBody(commit.diff())))) {
                throw new IllegalArgumentException("Invalid commit data");
            }
        }
        String checkpoint = batch.checkpointSha();
        if (checkpoint != null && (!checkpoint.matches("[0-9a-f]{40}|[0-9a-f]{64}") ||
                (!seen.contains(checkpoint) && !reviewedShas.contains(checkpoint)))) {
            throw new IllegalArgumentException("Invalid review checkpoint");
        }
    }

    private static boolean containsPatchBody(String diff) {
        return diff.lines().anyMatch(line -> line.startsWith("@@") ||
                (line.startsWith("+") && !line.startsWith("+++ ")) ||
                (line.startsWith("-") && !line.startsWith("--- ")));
    }

    private static void validateReview(ReviewResult review) {
        if (review == null || !textWithin(review.summary(), 32000) || review.findings() == null || review.findings().size() > 100) {
            throw new IllegalArgumentException("Invalid AI review result");
        }
        for (ReviewFinding finding : review.findings()) {
            if (finding == null || !SEVERITIES.contains(finding.severity()) || !textWithin(finding.title(), 240) ||
                    !textWithin(finding.filePath(), 1024) || !textWithin(finding.description(), 32000) || !textWithin(finding.suggestion(), 32000) ||
                    (finding.lineNumber() != null && finding.lineNumber() <= 0)) {
                throw new IllegalArgumentException("Invalid AI finding");
            }
        }
    }

    private static boolean textWithin(String value, int maximum) { return value != null && !value.isBlank() && value.length() <= maximum; }

    public enum Outcome { SUCCEEDED, FAILED, BUSY }
}
