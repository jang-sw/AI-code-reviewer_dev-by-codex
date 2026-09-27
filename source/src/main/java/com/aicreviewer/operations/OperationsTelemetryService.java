package com.aicreviewer.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Periodic aggregate observations; readers and metric scrapes never query the database. */
@Service
public class OperationsTelemetryService {
    public static final int STALE_AFTER_SECONDS = 90;
    public static final List<String> PROJECT_STATUSES = List.of("PENDING", "APPROVED", "REJECTED", "PAUSED");
    public static final List<String> REQUEST_STATES = List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final System.Logger LOG = System.getLogger(OperationsTelemetryService.class.getName());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int delayedAfterMinutes;
    private final Clock clock;
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final AtomicReference<CollectionState> state = new AtomicReference<>(new CollectionState(null, null, false));

    @Autowired
    public OperationsTelemetryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, MeterRegistry registry,
                                      @Value("${app.operations.stale-after-minutes:120}") int delayedAfterMinutes) {
        this(jdbc, transactionManager, registry, delayedAfterMinutes, Clock.systemUTC());
    }

    OperationsTelemetryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, MeterRegistry registry,
                               int delayedAfterMinutes, Clock clock) {
        if (delayedAfterMinutes < 1 || delayedAfterMinutes > 10080) {
            throw new IllegalArgumentException("Operational elapsed-time threshold must be between 1 and 10080 minutes");
        }
        this.jdbc = jdbc;
        this.delayedAfterMinutes = delayedAfterMinutes;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions.setReadOnly(true);
        registerMetrics(registry);
    }

    /** Concurrent triggers coalesce; only a fully committed observation replaces the cache. */
    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    public void refresh() {
        if (!collecting.compareAndSet(false, true)) return;
        Instant attemptedAt = clock.instant();
        try {
            Snapshot collected = transactions.execute(status -> collect(attemptedAt));
            if (collected == null) throw new IllegalStateException("Telemetry transaction returned no observation");
            state.set(new CollectionState(collected, attemptedAt, false));
        } catch (RuntimeException exception) {
            state.updateAndGet(previous -> new CollectionState(previous.snapshot(), attemptedAt, true));
            // Database errors can contain SQL, parameters or row contents. Never log the exception itself.
            LOG.log(System.Logger.Level.WARNING, "Operations observation failed: {0}", exception.getClass().getSimpleName());
        } finally {
            collecting.set(false);
        }
    }

    public Observation observation() {
        CollectionState current = state.get();
        Snapshot snapshot = current.snapshot();
        if (snapshot == null) {
            return new Observation(current.failed() ? "FAILED" : "STARTING", null, current.lastAttemptAt(), null,
                    delayedAfterMinutes, Map.of(), Map.of(), null, null, null);
        }
        long age = ageSeconds(snapshot.observedAt(), clock.instant());
        String status = current.failed() ? "FAILED" : age >= STALE_AFTER_SECONDS ? "STALE" : "READY";
        return new Observation(status, snapshot.observedAt(), current.lastAttemptAt(), age, delayedAfterMinutes,
                snapshot.projectCounts(), snapshot.requestCounts(), snapshot.oldestActiveRequestedAt(),
                snapshot.delayedActiveRequests(), snapshot.latestFailedProjects());
    }

    private Snapshot collect(Instant observedAt) {
        Map<String, Long> projectCounts = zeroCounts(PROJECT_STATUSES);
        jdbc.query("SELECT status, COUNT(*) AS total FROM project GROUP BY status", rs -> {
            putCount(projectCounts, rs.getString("status"), rs.getLong("total"));
        });
        Map<String, Long> requestCounts = zeroCounts(REQUEST_STATES);
        List<RequestAggregate> requests = jdbc.query("""
                SELECT state, COUNT(*) AS total,
                       MIN(CASE WHEN state IN ('QUEUED', 'RUNNING') THEN requested_at END) AS oldest_active_at,
                       SUM(CASE WHEN state IN ('QUEUED', 'RUNNING') AND requested_at <= ? THEN 1 ELSE 0 END) AS delayed
                FROM review_request GROUP BY state
                """, (rs, row) -> {
                    Timestamp oldest = rs.getTimestamp("oldest_active_at");
                    return new RequestAggregate(rs.getString("state"), rs.getLong("total"),
                            oldest == null ? null : oldest.toInstant(), rs.getLong("delayed"));
                }, Timestamp.from(observedAt.minus(Duration.ofMinutes(delayedAfterMinutes))));
        Instant oldestActive = null;
        long delayed = 0;
        for (RequestAggregate request : requests) {
            putCount(requestCounts, request.state(), request.total());
            if (request.oldest() != null && (oldestActive == null || request.oldest().isBefore(oldestActive))) oldestActive = request.oldest();
            delayed = Math.addExact(delayed, request.delayed());
        }
        Long failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project p JOIN review_run r ON r.id = (
                    SELECT latest.id FROM review_run latest WHERE latest.project_id = p.id ORDER BY latest.id DESC LIMIT 1
                ) WHERE r.status = 'FAILED'
                """, Long.class);
        if (failed == null || failed < 0 || delayed < 0) throw new IllegalStateException("Invalid aggregate observation");
        return new Snapshot(observedAt, immutableCounts(projectCounts), immutableCounts(requestCounts), oldestActive, delayed, failed);
    }

    private void registerMetrics(MeterRegistry registry) {
        Gauge.builder("ai.reviewer.observation.available", this, service -> service.observation().available() ? 1 : 0)
                .description("Whether the cached operational observation is current and its latest collection succeeded")
                .register(registry);
        Gauge.builder("ai.reviewer.observation.age.seconds", this, service -> {
            Long age = service.observation().ageSeconds();
            return age == null ? Double.NaN : age.doubleValue();
        }).description("Age of the last successful observation; unavailable before the first success").baseUnit("seconds").register(registry);
        for (String status : PROJECT_STATUSES) {
            Gauge.builder("ai.reviewer.projects", this, service -> service.currentValue(view -> view.projectCounts().get(status)))
                    .tag("status", status).description("Projects by approval status in the cached observation").register(registry);
        }
        for (String requestState : REQUEST_STATES) {
            Gauge.builder("ai.reviewer.requests", this, service -> service.currentValue(view -> view.requestCounts().get(requestState)))
                    .tag("state", requestState).description("Durable requests by state in the cached observation").register(registry);
        }
        Gauge.builder("ai.reviewer.requests.delayed", this, service -> service.currentValue(view -> view.delayedActiveRequests()))
                .description("Active requests at or beyond the configured elapsed-time threshold").register(registry);
        Gauge.builder("ai.reviewer.requests.oldest.active.age.seconds", this, service -> service.currentValue(view ->
                view.oldestActiveRequestedAt() == null ? 0 : ageSeconds(view.oldestActiveRequestedAt(), service.clock.instant())))
                .description("Age of the oldest active request; zero when no active requests were observed").baseUnit("seconds").register(registry);
        Gauge.builder("ai.reviewer.projects.latest.failed", this, service -> service.currentValue(view -> view.latestFailedProjects()))
                .description("Projects whose most recently recorded run failed").register(registry);
    }

    private double currentValue(ToDoubleFunction<Observation> value) {
        Observation observation = observation();
        return observation.available() ? value.applyAsDouble(observation) : Double.NaN;
    }

    private static long ageSeconds(Instant since, Instant now) { return Math.max(0L, Duration.between(since, now).getSeconds()); }
    private static Map<String, Long> zeroCounts(List<String> keys) {
        Map<String, Long> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, 0L));
        return result;
    }
    private static void putCount(Map<String, Long> counts, String key, long value) {
        if (!counts.containsKey(key) || value < 0) throw new IllegalStateException("Invalid aggregate observation");
        counts.put(key, value);
    }
    private static Map<String, Long> immutableCounts(Map<String, Long> counts) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private record RequestAggregate(String state, long total, Instant oldest, long delayed) { }
    private record Snapshot(Instant observedAt, Map<String, Long> projectCounts, Map<String, Long> requestCounts,
                            Instant oldestActiveRequestedAt, long delayedActiveRequests, long latestFailedProjects) { }
    private record CollectionState(Snapshot snapshot, Instant lastAttemptAt, boolean failed) { }

    public record Observation(String status, Instant observedAt, Instant lastAttemptAt, Long ageSeconds,
                              int delayedAfterMinutes, Map<String, Long> projectCounts, Map<String, Long> requestCounts,
                              Instant oldestActiveRequestedAt, Long delayedActiveRequests, Long latestFailedProjects) {
        public Observation {
            projectCounts = immutableCounts(projectCounts);
            requestCounts = immutableCounts(requestCounts);
        }
        public boolean available() { return "READY".equals(status); }
    }
}
