package com.aicreviewer.operations;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ReviewTestDatabase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringAccessServiceTest {
    private static final Instant OBSERVED = Instant.parse("2026-09-26T22:00:00Z");
    private ReviewTestDatabase db;
    private OperationsTelemetryService telemetry;
    private MonitoringAccessService monitoring;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        var users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), new AuditEventWriter(db.jdbc));
        telemetry = mock(OperationsTelemetryService.class);
        monitoring = new MonitoringAccessService(users, telemetry, false, true);
    }

    @AfterEach void close() { db.close(); }

    @Test void onlyCurrentAdministratorCanReadTheCache() {
        assertDenied(null, 401);
        assertDenied("owner", 403);
        db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='admin'");
        assertDenied("admin", 401);
        db.jdbc.update("UPDATE app_user SET enabled=TRUE,role='USER' WHERE username='admin'");
        assertDenied("admin", 403);
        db.jdbc.update("UPDATE app_user SET enabled=FALSE,approval_status='PENDING' WHERE username='admin'");
        assertDenied("admin", 401);
        verifyNoInteractions(telemetry);
    }

    @Test void readsOneCachedObservationAndExposesOnlyFixedAggregateKeysAndServerFlags() {
        when(telemetry.observation()).thenReturn(observation("READY"));
        var snapshot = monitoring.snapshot("admin");
        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.projectCounts()).containsOnlyKeys("PENDING", "APPROVED", "REJECTED", "PAUSED");
        assertThat(snapshot.requestCounts()).containsOnlyKeys("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
        assertThat(snapshot.projectCounts().get("APPROVED")).isEqualTo(4);
        assertThat(snapshot.requestCounts().get("RUNNING")).isEqualTo(2);
        assertThat(snapshot.latestFailedProjects()).isEqualTo(3);
        assertThat(snapshot.delayedActiveRequests()).isEqualTo(1);
        assertThat(snapshot.currentServerWorkerEnabled()).isFalse();
        assertThat(snapshot.currentServerSchedulerEnabled()).isTrue();
        assertThat(snapshot.toString()).doesNotContain("unexpected-private-field", "username", "password", "requestId", "claimToken");
        assertThatThrownBy(() -> snapshot.projectCounts().put("APPROVED", 99L)).isInstanceOf(UnsupportedOperationException.class);
        verify(telemetry).observation();
        verifyNoMoreInteractions(telemetry);
        assertThat(db.count("audit_event")).isZero();
        assertThat(db.count("review_run")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "STALE", "STARTING", "unknown-private-state"})
    void unavailableObservationKeepsAgeButDoesNotPublishHistoricalCountsAsCurrent(String state) {
        when(telemetry.observation()).thenReturn(observation(state));
        var snapshot = monitoring.snapshot("admin");
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.status()).isEqualTo(state.equals("unknown-private-state") ? "FAILED" : state);
        assertThat(snapshot.observedAt()).isEqualTo(OBSERVED);
        assertThat(snapshot.lastAttemptAt()).isEqualTo(OBSERVED.plusSeconds(30));
        assertThat(snapshot.ageSeconds()).isEqualTo(100);
        assertThat(snapshot.projectCounts()).isEmpty();
        assertThat(snapshot.requestCounts()).isEmpty();
        assertThat(snapshot.latestFailedProjects()).isNull();
        assertThat(snapshot.delayedActiveRequests()).isNull();
        assertThat(snapshot.oldestActiveRequestedAt()).isNull();
    }

    @Test void firstObservationIsUnavailableWithNoInventedTimestampsOrZeroCounts() {
        when(telemetry.observation()).thenReturn(new OperationsTelemetryService.Observation(
                "STARTING", null, null, null, 120, Map.of(), Map.of(), null, null, null));
        var snapshot = monitoring.snapshot("admin");
        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.observedAt()).isNull();
        assertThat(snapshot.ageSeconds()).isNull();
        assertThat(snapshot.projectCounts()).isEmpty();
        assertThat(snapshot.latestFailedProjects()).isNull();
    }

    private void assertDenied(String username, int status) {
        assertThatThrownBy(() -> monitoring.snapshot(username)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
    }

    private static OperationsTelemetryService.Observation observation(String status) {
        return new OperationsTelemetryService.Observation(status, OBSERVED, OBSERVED.plusSeconds(30), 100L, 120,
                Map.of("PENDING", 1L, "APPROVED", 4L, "REJECTED", 0L, "PAUSED", 2L, "unexpected-private-field", 99L),
                Map.of("QUEUED", 3L, "RUNNING", 2L, "SUCCEEDED", 1L, "FAILED", 1L, "CANCELLED", 0L),
                OBSERVED.minusSeconds(7200), 1L, 3L);
    }
}
