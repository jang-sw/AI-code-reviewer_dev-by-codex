package com.aicreviewer.review;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** PostgreSQL owns the pending work; this executor only limits local execution. */
@Service
public class ReviewDispatcher {
    static final int MAX_CONCURRENCY = 16;
    static final int MAX_SCHEDULE_CANDIDATES = 1016;
    static final int POLL_CANDIDATES = 64;
    private static final System.Logger LOG = System.getLogger(ReviewDispatcher.class.getName());
    private final ReviewCoordinator coordinator;
    private final ReviewRequestRepository requests;
    private final boolean workerEnabled;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReviewDispatcher(ReviewCoordinator coordinator, ReviewRequestRepository requests, DataSource dataSource,
                            @Value("${app.review.concurrency:2}") int concurrency,
                            @Value("${app.review.worker-enabled:true}") boolean workerEnabled) {
        this(coordinator, requests, concurrency, dataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : null, workerEnabled);
    }

    ReviewDispatcher(ReviewCoordinator coordinator, ReviewRequestRepository requests, int concurrency) {
        this(coordinator, requests, concurrency, null);
    }

    ReviewDispatcher(ReviewCoordinator coordinator, ReviewRequestRepository requests, int concurrency, Integer connectionPoolSize) {
        this(coordinator, requests, concurrency, connectionPoolSize, true);
    }

    ReviewDispatcher(ReviewCoordinator coordinator, ReviewRequestRepository requests, int concurrency, Integer connectionPoolSize, boolean workerEnabled) {
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) throw new IllegalArgumentException("Review concurrency must be between 1 and 16");
        if (connectionPoolSize != null && connectionPoolSize < 2 * concurrency + 2) {
            throw new IllegalArgumentException("JDBC pool must have at least 2 * app.review.concurrency + 2 connections for review locks, transactions and web requests");
        }
        this.coordinator = coordinator;
        this.requests = requests;
        this.workerEnabled = workerEnabled;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().name("project-review-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public Submission submitManual(long projectId, String username) {
        // Authorization and durable enqueue commit happen together. A later dispatch
        // failure must not turn an accepted request into an ambiguous HTTP failure.
        var result = requests.enqueueManual(projectId, username, Instant.now());
        drain();
        return result == ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED
                ? Submission.ALREADY_QUEUED : Submission.QUEUED;
    }

    public boolean isQueued(long projectId) { return requests.isActive(projectId); }

    /** Runs even when creation of new scheduled requests is disabled. */
    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public synchronized void drain() {
        if (!workerEnabled || executor.isShutdown() || inFlight.size() >= executor.getMaximumPoolSize()) return;
        try {
            for (var request : requests.candidates(Instant.now(), POLL_CANDIDATES)) {
                if (executor.isShutdown() || inFlight.size() >= executor.getMaximumPoolSize()) break;
                if (!inFlight.add(request.projectId())) continue;
                try {
                    executor.execute(() -> execute(request));
                } catch (RejectedExecutionException exception) {
                    inFlight.remove(request.projectId());
                    break; // The DB request remains available to the next poll or process.
                }
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Review queue dispatch failed: {0}", exception.getClass().getSimpleName());
        }
    }

    private void execute(ReviewRequestRepository.Request request) {
        try {
            coordinator.processRequest(request);
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Project {0} review execution failed: {1}",
                    request.projectId(), exception.getClass().getSimpleName());
        } finally {
            inFlight.remove(request.projectId());
        }
    }

    @PreDestroy
    public void close() { close(20, TimeUnit.SECONDS); }

    void close(long wait, TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(wait, unit)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public enum Submission { QUEUED, ALREADY_QUEUED }
}
