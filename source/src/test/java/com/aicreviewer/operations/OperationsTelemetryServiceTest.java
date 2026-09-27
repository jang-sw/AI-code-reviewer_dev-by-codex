package com.aicreviewer.operations;

import com.aicreviewer.review.ReviewTestDatabase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsTelemetryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-27T04:30:00Z");
    private ReviewTestDatabase db;
    private JdbcTemplate jdbc;
    private RecordingTransactions transactions;
    private SimpleMeterRegistry metrics;
    private MutableClock clock;
    private OperationsTelemetryService service;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        jdbc = spy(db.jdbc);
        transactions = new RecordingTransactions(jdbc.getDataSource());
        metrics = new SimpleMeterRegistry();
        clock = new MutableClock(NOW);
        service = new OperationsTelemetryService(jdbc, transactions, metrics, 120, clock);
    }

    @AfterEach void close() {
        transactions.releaseCommit.countDown();
        metrics.close();
        db.close();
    }

    @Test void beforeFirstSuccessCountsAreUnknownRatherThanHealthyZeros() {
        var observation = service.observation();
        assertThat(observation.status()).isEqualTo("STARTING");
        assertThat(observation.available()).isFalse();
        assertThat(observation.observedAt()).isNull();
        assertThat(observation.lastAttemptAt()).isNull();
        assertThat(observation.ageSeconds()).isNull();
        assertThat(observation.projectCounts()).isEmpty();
        assertThat(observation.requestCounts()).isEmpty();
        assertThat(observation.delayedActiveRequests()).isNull();
        assertThat(observation.latestFailedProjects()).isNull();
        assertThat(gauge("ai.reviewer.observation.available")).isZero();
        assertThat(gauge("ai.reviewer.observation.age.seconds")).isNaN();
        assertUnavailableDataGauges();
    }

    @Test void aggregatesAllStatesAtTheInclusiveDelayBoundaryAndOnlyTheLatestRunPerProject() {
        project(11, "PENDING");
        project(12, "REJECTED");
        project(13, "PAUSED");
        for (long id = 14; id <= 17; id++) project(id, "APPROVED");
        request(10, "QUEUED", NOW.minusSeconds(7200));
        request(11, "RUNNING", NOW.minusSeconds(7201));
        request(12, "QUEUED", NOW.minusSeconds(7199));
        request(13, "SUCCEEDED", NOW.minusSeconds(20000));
        request(14, "FAILED", NOW.minusSeconds(20000));
        request(15, "CANCELLED", NOW.minusSeconds(20000));
        request(16, "QUEUED", NOW.plusSeconds(1));
        run(10, "FAILED");
        run(10, "SUCCEEDED");
        run(13, "FAILED");
        run(17, "FAILED");
        run(17, "RUNNING");

        service.refresh();
        var observation = service.observation();
        assertThat(observation.status()).isEqualTo("READY");
        assertThat(observation.available()).isTrue();
        assertThat(observation.observedAt()).isEqualTo(NOW);
        assertThat(observation.lastAttemptAt()).isEqualTo(NOW);
        assertThat(observation.ageSeconds()).isZero();
        assertThat(observation.delayedAfterMinutes()).isEqualTo(120);
        assertThat(observation.projectCounts()).isEqualTo(Map.of("PENDING", 1L, "APPROVED", 5L, "REJECTED", 1L, "PAUSED", 1L));
        assertThat(observation.requestCounts()).isEqualTo(Map.of("QUEUED", 3L, "RUNNING", 1L, "SUCCEEDED", 1L, "FAILED", 1L, "CANCELLED", 1L));
        assertThat(observation.oldestActiveRequestedAt()).isEqualTo(NOW.minusSeconds(7201));
        assertThat(observation.delayedActiveRequests()).isEqualTo(2);
        assertThat(observation.latestFailedProjects()).isEqualTo(1); // Includes the paused project; older failures do not count.
        assertThat(gauge("ai.reviewer.requests.delayed")).isEqualTo(2);
        assertThat(gauge("ai.reviewer.requests.oldest.active.age.seconds")).isEqualTo(7201);
        assertThat(gauge("ai.reviewer.projects.latest.failed")).isEqualTo(1);
    }

    @Test void successfulEmptyGroupsAreExplicitZerosAndCannotBeMutatedByReaders() {
        service.refresh();
        var observation = service.observation();
        assertThat(observation.projectCounts()).containsExactly(
                entry("PENDING", 0L), entry("APPROVED", 1L), entry("REJECTED", 0L), entry("PAUSED", 0L));
        assertThat(observation.requestCounts()).containsExactly(
                entry("QUEUED", 0L), entry("RUNNING", 0L), entry("SUCCEEDED", 0L), entry("FAILED", 0L), entry("CANCELLED", 0L));
        assertThat(observation.oldestActiveRequestedAt()).isNull();
        assertThat(observation.delayedActiveRequests()).isZero();
        assertThat(observation.latestFailedProjects()).isZero();
        assertThat(gauge("ai.reviewer.requests.oldest.active.age.seconds")).isZero();
        assertThatThrownBy(() -> observation.projectCounts().put("APPROVED", 999L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observation.requestCounts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void ninetySecondsIsStaleAndBackwardClockMovementNeverProducesNegativeAges() {
        service.refresh();
        clock.set(NOW.plusSeconds(89).plusNanos(999_999_999));
        assertThat(service.observation().status()).isEqualTo("READY");
        clock.set(NOW.plusSeconds(90));
        assertThat(service.observation().status()).isEqualTo("STALE");
        assertThat(service.observation().projectCounts()).containsEntry("APPROVED", 1L);
        assertThat(gauge("ai.reviewer.observation.available")).isZero();
        assertThat(gauge("ai.reviewer.observation.age.seconds")).isEqualTo(90);
        assertUnavailableDataGauges();
        clock.set(NOW.minusSeconds(20));
        assertThat(service.observation().ageSeconds()).isZero();
        assertThat(service.observation().status()).isEqualTo("READY");
        service.refresh();
        assertThat(service.observation().observedAt()).isEqualTo(NOW.minusSeconds(20));
    }

    @Test void firstFailureRemainsUnknownAndLaterSuccessClearsTheFailure() {
        transactions.failCommit = true;
        service.refresh();
        assertThat(service.observation().status()).isEqualTo("FAILED");
        assertThat(service.observation().observedAt()).isNull();
        assertThat(service.observation().projectCounts()).isEmpty();
        assertThat(service.observation().lastAttemptAt()).isEqualTo(NOW);
        assertUnavailableDataGauges();
        transactions.failCommit = false;
        clock.set(NOW.plusSeconds(30));
        service.refresh();
        assertThat(service.observation().status()).isEqualTo("READY");
        assertThat(service.observation().observedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test void databaseFailureRollsBackPartialCollectionAndPreservesLastSuccessfulValues() {
        service.refresh();
        var previous = service.observation();
        project(11, "PAUSED");
        db.jdbc.execute("ALTER TABLE review_request RENAME COLUMN state TO unavailable_state_fixture");
        clock.set(NOW.plusSeconds(31));
        service.refresh();
        var failed = service.observation();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.projectCounts()).isEqualTo(previous.projectCounts());
        assertThat(failed.requestCounts()).isEqualTo(previous.requestCounts());
        assertThat(failed.observedAt()).isEqualTo(NOW);
        assertThat(failed.lastAttemptAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(failed.ageSeconds()).isEqualTo(31);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(gauge("ai.reviewer.observation.available")).isZero();
        assertThat(gauge("ai.reviewer.observation.age.seconds")).isEqualTo(31);
        assertUnavailableDataGauges();
        clock.set(NOW.plusSeconds(200));
        assertThat(service.observation().status()).isEqualTo("FAILED"); // Latest failed attempt is explicit even after the age threshold.
    }

    @Test void commitFailureDoesNotPublishTheFreshlyCollectedValues() {
        service.refresh();
        project(11, "APPROVED");
        transactions.failCommit = true;
        clock.set(NOW.plusSeconds(30));
        service.refresh();
        assertThat(service.observation().status()).isEqualTo("FAILED");
        assertThat(service.observation().projectCounts()).containsEntry("APPROVED", 1L);
        assertThat(service.observation().observedAt()).isEqualTo(NOW);
        assertThat(transactions.rollbacks).isEqualTo(1);
        transactions.failCommit = false;
        service.refresh();
        assertThat(service.observation().projectCounts()).containsEntry("APPROVED", 2L);
    }

    @Test void collectionFailureLogContainsOnlyTheExceptionType() {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(OperationsTelemetryService.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            transactions.failCommit = true;
            service.refresh();
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(records).hasSize(1);
        var record = records.getFirst();
        assertThat(java.text.MessageFormat.format(record.getMessage(), record.getParameters()))
                .isEqualTo("Operations observation failed: TransactionSystemException");
        assertThat(record.getThrown()).isNull();
        assertThat(service.observation().toString()).doesNotContain("private-sql-parameter-fixture");
    }

    @Test void collectionUsesItsOwnReadOnlyRepeatableReadTransaction() {
        var outer = new TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> service.refresh());
        assertThat(transactions.definitions).hasSize(2);
        assertThat(transactions.definitions.getLast()).isEqualTo(new Definition(true,
                TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertThat(service.observation().status()).isEqualTo("READY");
    }

    @Test void concurrentReadersSeeThePreviousSnapshotUntilCommitAndExtraTriggersCoalesce() throws Exception {
        service.refresh();
        project(11, "PAUSED");
        clock.set(NOW.plusSeconds(5));
        transactions.holdCommit = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var refreshing = executor.submit(service::refresh);
            try {
                assertThat(transactions.commitEntered.await(3, TimeUnit.SECONDS)).isTrue();
                for (int read = 0; read < 20; read++) {
                    assertThat(service.observation().projectCounts()).containsEntry("PAUSED", 0L);
                    assertThat(service.observation().observedAt()).isEqualTo(NOW);
                    assertThat(gauge("ai.reviewer.projects", "status", "PAUSED")).isZero();
                }
                service.refresh(); // Must return without waiting for the current collection or starting another transaction.
                assertThat(transactions.definitions).hasSize(2);
            } finally {
                transactions.releaseCommit.countDown();
            }
            refreshing.get(3, TimeUnit.SECONDS);
        }
        assertThat(service.observation().projectCounts()).containsEntry("PAUSED", 1L);
        assertThat(service.observation().observedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(transactions.definitions).hasSize(2);
    }

    @Test void observationsAndMetricScrapesNeverReadTheDatabaseAndTagsHaveFixedCardinality() {
        service.refresh();
        clearInvocations(jdbc);
        for (int read = 0; read < 5; read++) {
            service.observation();
            metrics.getMeters().forEach(meter -> ((Gauge) meter).value());
        }
        verifyNoInteractions(jdbc);
        assertThat(metrics.getMeters()).hasSize(14);
        assertThat(metrics.find("ai.reviewer.projects").gauges()).hasSize(4);
        assertThat(metrics.find("ai.reviewer.requests").gauges()).hasSize(5);
        metrics.getMeters().forEach(meter -> {
            assertThat(meter.getId().getName()).startsWith("ai.reviewer.");
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getKey()).isIn("status", "state");
                assertThat(tag.getValue()).isIn("PENDING", "APPROVED", "REJECTED", "PAUSED", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
            });
        });
    }

    @Test void configuredRequestDelayThresholdIsAppliedAndInvalidThresholdsFailAtStartup() {
        var customMetrics = new SimpleMeterRegistry();
        try {
            var custom = new OperationsTelemetryService(jdbc, transactions, customMetrics, 1, clock);
            request(10, "QUEUED", NOW.minusSeconds(60));
            custom.refresh();
            assertThat(custom.observation().delayedAfterMinutes()).isEqualTo(1);
            assertThat(custom.observation().delayedActiveRequests()).isEqualTo(1);
        } finally {
            customMetrics.close();
        }
        for (int invalid : new int[] {0, -1, 10081}) {
            assertThatThrownBy(() -> new OperationsTelemetryService(jdbc, transactions, metrics, invalid, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void assertUnavailableDataGauges() {
        metrics.getMeters().stream().filter(meter -> !meter.getId().getName().startsWith("ai.reviewer.observation."))
                .forEach(meter -> assertThat(((Gauge) meter).value()).isNaN());
    }
    private double gauge(String name, String... tags) { return metrics.get(name).tags(tags).gauge().value(); }
    private void project(long id, String status) {
        db.jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES(?,?,?,'GITHUB','github.com',?,1,?)",
                id, "Telemetry fixture " + id, "https://github.com/org/telemetry" + id, "org/telemetry" + id, status);
    }
    private long run(long projectId, String status) {
        db.jdbc.update("INSERT INTO review_run(project_id,status) VALUES(?,?)", projectId, status);
        return db.jdbc.queryForObject("SELECT MAX(id) FROM review_run WHERE project_id=?", Long.class, projectId);
    }
    private void request(long projectId, String state, Instant requested) {
        Long runId = state.equals("RUNNING") ? run(projectId, "RUNNING") : null;
        boolean terminal = List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state);
        db.jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_at,available_at,claim_token,run_id,finished_at) VALUES(?,?,?,'SCHEDULED',?,?,?,?,?)",
                projectId, UUID.randomUUID().toString(), state, Timestamp.from(requested), Timestamp.from(requested),
                runId == null ? null : UUID.randomUUID().toString(), runId, terminal ? Timestamp.from(NOW) : null);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private record Definition(boolean readOnly, int isolation, int propagation) { }
    private static final class RecordingTransactions extends DataSourceTransactionManager {
        final List<Definition> definitions = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch commitEntered = new CountDownLatch(1);
        final CountDownLatch releaseCommit = new CountDownLatch(1);
        volatile boolean failCommit;
        volatile boolean holdCommit;
        int rollbacks;
        RecordingTransactions(DataSource dataSource) { super(dataSource); setRollbackOnCommitFailure(true); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            definitions.add(new Definition(definition.isReadOnly(), definition.getIsolationLevel(), definition.getPropagationBehavior()));
            super.doBegin(transaction, definition);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (holdCommit) {
                commitEntered.countDown();
                try {
                    if (!releaseCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture commit release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture commit interrupted");
                }
            }
            if (failCommit) throw new TransactionSystemException("private-sql-parameter-fixture");
            super.doCommit(status);
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; super.doRollback(status); }
    }
}
