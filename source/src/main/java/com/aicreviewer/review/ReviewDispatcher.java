package com.aicreviewer.review;

import jakarta.annotation.PreDestroy;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Bounded background work keeps slow AI calls out of servlet request threads. */
@Service
public class ReviewDispatcher {
    static final int QUEUE_CAPACITY = 1000;
    static final int MAX_CONCURRENCY = 16;
    static final int MAX_SCHEDULE_CANDIDATES = QUEUE_CAPACITY + MAX_CONCURRENCY;
    private static final System.Logger LOG = System.getLogger(ReviewDispatcher.class.getName());
    private final ReviewCoordinator coordinator;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReviewDispatcher(ReviewCoordinator coordinator, DataSource dataSource, @Value("${app.review.concurrency:2}") int concurrency) {
        this(coordinator, concurrency, dataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : null);
    }

    ReviewDispatcher(ReviewCoordinator coordinator, int concurrency) {
        this(coordinator, concurrency, null);
    }

    ReviewDispatcher(ReviewCoordinator coordinator, int concurrency, Integer connectionPoolSize) {
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) throw new IllegalArgumentException("Review concurrency must be between 1 and 16");
        if (connectionPoolSize != null && connectionPoolSize < 2 * concurrency + 2) {
            throw new IllegalArgumentException("JDBC pool must have at least 2 * app.review.concurrency + 2 connections for review locks, transactions and web requests");
        }
        this.coordinator = coordinator;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), Thread.ofPlatform().name("project-review-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public Submission submitManual(long projectId, String username) {
        coordinator.authorizeManual(projectId, username);
        return submit(projectId, username);
    }

    Submission submitScheduled(long projectId) { return submit(projectId, null); }

    private Submission submit(long projectId, String username) {
        if (!inFlight.add(projectId)) return Submission.ALREADY_QUEUED;
        try {
            executor.execute(new ReviewTask(projectId, username));
            return Submission.QUEUED;
        } catch (RejectedExecutionException exception) {
            inFlight.remove(projectId);
            return Submission.CAPACITY_REACHED;
        }
    }

    public boolean isQueued(long projectId) { return inFlight.contains(projectId); }

    private final class ReviewTask implements Runnable {
        private final long projectId;
        private final String username;
        private ReviewTask(long projectId, String username) { this.projectId = projectId; this.username = username; }
        @Override public void run() {
            try {
                coordinator.reviewProject(projectId, username);
            } catch (RuntimeException exception) {
                LOG.log(System.Logger.Level.WARNING, "Project {0} review execution failed: {1}", projectId, exception.getClass().getSimpleName());
            } finally {
                inFlight.remove(projectId);
            }
        }
        void discard() { inFlight.remove(projectId); }
    }

    /**
     * Query enough candidates to get past every locally queued project, while bounding JDBC allocation.
     * Limiting to only the currently free slots would let old in-flight IDs hide eligible later IDs.
     * Executor state can change after this snapshot; submit still handles saturation atomically.
     */
    int scheduledCandidateLimit() {
        if (executor.isShutdown() || (executor.getQueue().remainingCapacity() == 0
                && executor.getActiveCount() >= executor.getMaximumPoolSize())) return 0;
        return QUEUE_CAPACITY + executor.getMaximumPoolSize();
    }

    @PreDestroy
    public void close() {
        close(20, TimeUnit.SECONDS);
    }

    void close(long wait, TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(wait, unit)) discardQueuedTasks();
        } catch (InterruptedException exception) {
            discardQueuedTasks();
            Thread.currentThread().interrupt();
        }
    }

    private void discardQueuedTasks() {
        // shutdownNow only returns tasks that never started. Running tasks retain their
        // reservation until their own finally block has actually finished.
        for (Runnable task : executor.shutdownNow()) ((ReviewTask) task).discard();
    }

    public enum Submission { QUEUED, ALREADY_QUEUED, CAPACITY_REACHED }
}
