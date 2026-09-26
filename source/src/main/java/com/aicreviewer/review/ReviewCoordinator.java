package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.AiInputLimitException;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final Set<String> COVERAGE_TYPES = Set.of("FULL", "EMPTY", "METADATA_ONLY", "MANUAL_ONLY");
    private final ReviewRepository repository;
    private final ProjectReviewLock locks;
    private final GitRepositoryClient git;
    private final AiReviewClient ai;
    private final TransactionTemplate transactions;
    private final int maxCommits;
    private final ReviewRequestRepository requests;

    public ReviewCoordinator(ReviewRepository repository, ProjectReviewLock locks, GitRepositoryClient git,
                             AiReviewClient ai, PlatformTransactionManager transactionManager,
                             int maxCommits) {
        this(repository, locks, git, ai, transactionManager, maxCommits, null);
    }

    @Autowired
    public ReviewCoordinator(ReviewRepository repository, ProjectReviewLock locks, GitRepositoryClient git,
                             AiReviewClient ai, PlatformTransactionManager transactionManager,
                             @Value("${app.review.max-commits:100}") int maxCommits, ReviewRequestRepository requests) {
        if (maxCommits < 1 || maxCommits > 1000) throw new IllegalArgumentException("Review batch size must be between 1 and 1000");
        this.repository = repository;
        this.locks = locks;
        this.git = git;
        this.ai = ai;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxCommits = maxCommits;
        this.requests = requests;
    }

    public void authorizeManual(long projectId, String username) {
        ReviewRepository.requireApproved(repository.authorizedProject(projectId, repository.actor(username)));
    }

    public List<Long> scheduledProjects(int limit) { return repository.approvedProjectIds(limit); }

    /** Runtime entry point: the database request, not a local executor task, owns durable progress. */
    public Outcome processRequest(ReviewRequestRepository.Request request) {
        if (requests == null) throw new IllegalStateException("Durable review requests are not configured");
        var lease = locks.tryAcquire(request.projectId());
        if (lease.isEmpty()) {
            requests.deferBusy(request, Instant.now().plusSeconds(ReviewRequestRepository.RETRY_SECONDS));
            return Outcome.BUSY;
        }
        try (var heldLock = lease.get()) {
            ReviewRequestRepository.Claim claim = requests.claim(request, Instant.now());
            if (claim == null) return Outcome.SKIPPED;
            // Claim already authorizes under row locks; later calls recheck before external I/O and writes.
            ReviewProject project = repository.project(request.projectId(), false);
            return executeBatch(project, claim.runId(), claim);
        }
    }

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
            return executeBatch(project, runId, null);
        }
    }

    private Outcome executeBatch(ReviewProject project, long runId, ReviewRequestRepository.Claim claim) {
        long projectId = project.id();
        try {
            checkInterrupted();
            assertCurrentRequest(claim);
            Set<String> reviewedShas = repository.reviewedShas(projectId);
            GitReviewBatch batch = git.batch(project.repository(), project.branch(), project.lastReviewedSha(), reviewedShas, maxCommits);
            validateBatch(batch, reviewedShas);
            List<GitCommit> commits = batch.commits();
            for (GitCommit commit : commits) {
                checkInterrupted();
                assertCurrentRequest(claim);
                // Retries never create a second issue set or move a cursor backwards.
                if (repository.alreadyReviewed(projectId, commit.sha())) continue;
                PreparedReview prepared = prepareReview(project, commit);
                validateReview(prepared.result());
                transactions.execute(status -> {
                    checkInterrupted();
                    if (claim != null) requests.guard(claim);
                    boolean persisted = repository.persistCommit(runId, project, project.lastReviewedSha(), prepared.commit(), prepared.result(), Instant.now());
                    checkInterrupted();
                    return persisted;
                });
            }
            // A partial merge can persist progress without a checkpoint. Conversely, a
            // fully persisted retry can advance to a safe checkpoint with no new commits.
            transactions.executeWithoutResult(status -> {
                checkInterrupted();
                if (claim == null) repository.completeBatch(runId, project, batch.checkpointSha(), Instant.now());
                else requests.complete(claim, project, batch.checkpointSha(), Instant.now());
                checkInterrupted();
            });
            return Outcome.SUCCEEDED;
        } catch (RuntimeException exception) {
            if (claim != null && (exception instanceof ReviewRequestRepository.StaleClaimException || interrupted(exception))) {
                // Ownership replacement and interrupted shutdown never acknowledge away durable work.
                return Outcome.SKIPPED;
            }
            if (claim != null && databaseFailure(exception)) {
                // A failed commit response can mean either rollback or a committed acknowledgement.
                // Leave the durable state unchanged; the next lease owner reconciles stored progress.
                throw exception;
            }
            // IntegrationException has an explicit safe-message contract. Other exception
            // messages may include source code or credentials and must never reach persistence.
            String reason = exception.getClass().getSimpleName();
            if (exception instanceof IntegrationException && exception.getMessage() != null) {
                reason = exception.getMessage().replace('\n', ' ').replace('\r', ' ');
                if (reason.length() > 500) reason = reason.substring(0, 500);
            }
            String safeError = "리뷰 처리 실패: " + reason + ". 저장된 커밋 리뷰를 재사용하여 재시도할 수 있습니다.";
            if (claim == null) transactions.executeWithoutResult(status -> repository.finishRun(runId, false, Instant.now(), safeError));
            else {
                boolean acknowledged = Boolean.TRUE.equals(transactions.execute(status -> requests.fail(claim, safeError, Instant.now())));
                if (!acknowledged) return Outcome.SKIPPED;
            }
            LOG.log(System.Logger.Level.WARNING, "Review run {0} for project {1} failed: {2}", runId, projectId, exception.getClass().getSimpleName());
            return exception instanceof ReviewRequestRepository.RequestCancelledException ? Outcome.CANCELLED : Outcome.FAILED;
        }
    }

    private void assertCurrentRequest(ReviewRequestRepository.Claim claim) {
        if (claim != null) transactions.executeWithoutResult(status -> requests.guard(claim));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Review interrupted");
    }

    private static boolean interrupted(RuntimeException exception) {
        if (Thread.currentThread().isInterrupted()) return true;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = exception; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    private static boolean databaseFailure(RuntimeException exception) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = exception; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof org.springframework.dao.DataAccessException
                    || current instanceof org.springframework.transaction.TransactionException
                    || current instanceof java.sql.SQLException) return true;
        }
        return false;
    }

    private PreparedReview prepareReview(ReviewProject project, GitCommit commit) {
        if ("METADATA_ONLY".equals(commit.coverageType())) {
            return verifiedManualReview(commit, git.manualMetadataFallback(project.repository(), commit), "METADATA_CHANGE");
        }
        try {
            return new PreparedReview(commit, reviewContent(commit));
        } catch (AiInputLimitException limit) {
            // Only a known, preflight input limit can become manual work. HTTP, timeout,
            // refusal and malformed output still fail the run and cannot move its cursor.
            if (!"FULL".equals(commit.coverageType())) throw limit;
            return verifiedManualReview(commit, git.manualFallback(project.repository(), commit), "AI_INPUT_LIMIT");
        }
    }

    private PreparedReview verifiedManualReview(GitCommit commit, GitCommit manual, String reason) {
        if (manual == null || !"MANUAL_ONLY".equals(manual.coverageType()) || !commit.sha().equals(manual.sha())
                || !java.util.Objects.equals(commit.authorLogin(), manual.authorLogin())
                || !java.util.Objects.equals(commit.authorEmail(), manual.authorEmail())
                || !commit.message().equals(manual.message())
                || manual.manualFiles().stream().anyMatch(file -> !reason.equals(file.reasonCode()))) {
            throw new IntegrationException("Manual review evidence does not match the original commit or reason");
        }
        validateBatch(new GitReviewBatch(List.of(manual), manual.sha()), Set.of());
        return new PreparedReview(manual, reviewContent(manual));
    }

    private record PreparedReview(GitCommit commit, ReviewResult result) { }

    private ReviewResult reviewContent(GitCommit commit) {
        return switch (commit.coverageType()) {
            case "EMPTY" -> new ReviewResult("AI 본문 검토 없음: Git 저장소에서 파일 변경이 없는 커밋임을 확인했습니다.", List.of());
            case "MANUAL_ONLY" -> new ReviewResult("AI 본문 검토 없음: 이 커밋의 전체 변경 경로 " + commit.manualFiles().size()
                    + "개를 수동 확인 이슈로 배정했습니다. 이슈 처리는 별도로 필요하며 다음 커밋 리뷰는 계속 진행합니다.", List.of());
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
                    ("MANUAL_ONLY".equals(commit.coverageType()) && (!commit.diff().isBlank() || commit.coverageDetails().isBlank())) ||
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

    public enum Outcome { SUCCEEDED, FAILED, BUSY, SKIPPED, CANCELLED }
}
