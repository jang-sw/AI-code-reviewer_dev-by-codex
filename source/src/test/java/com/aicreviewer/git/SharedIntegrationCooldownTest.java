package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.*;

import com.aicreviewer.review.ReviewTestDatabase;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class SharedIntegrationCooldownTest {
    private final ReviewTestDatabase database = new ReviewTestDatabase();
    private final SharedIntegrationCooldown first = new SharedIntegrationCooldown(database.jdbc, database.transactionManager);
    private final SharedIntegrationCooldown second = new SharedIntegrationCooldown(database.jdbc, database.transactionManager);
    private static final URI ENDPOINT = URI.create("https://service.example.invalid/api/repository?token=synthetic-private");
    @AfterEach void close() { database.close(); }

    @Test void canonicalOriginSharesPathsAndDefaultPortsButSeparatesServiceHostSchemeAndPort() {
        Instant until = Instant.now().plusSeconds(300);
        first.onRateLimited(RateLimitedException.Service.GIT, ENDPOINT, until);
        Throwable caught = catchThrowable(() -> second.beforeRequest(RateLimitedException.Service.GIT,
                URI.create("https://SERVICE.example.invalid:443/another/path?secret=other")));
        assertThat(caught).isInstanceOf(RateLimitedException.class).hasNoCause();
        var limited = (RateLimitedException) caught;
        assertThat(limited.actualResponse()).isFalse();
        assertThat(limited.service()).isEqualTo(RateLimitedException.Service.GIT);
        assertThat(limited.retryAt()).isBetween(until.minusNanos(1000), until.plusNanos(1000));
        assertThat(limited.getMessage()).doesNotContain("example", "private", "token", "another", "secret");
        second.beforeRequest(RateLimitedException.Service.AI, ENDPOINT);
        second.beforeRequest(RateLimitedException.Service.GIT, URI.create("http://service.example.invalid/"));
        second.beforeRequest(RateLimitedException.Service.GIT, URI.create("https://service.example.invalid:444/"));
        second.beforeRequest(RateLimitedException.Service.GIT, URI.create("https://another.example.invalid/"));
        var row = database.jdbc.queryForMap("SELECT * FROM integration_cooldown");
        assertThat(row.keySet()).containsExactlyInAnyOrder("service", "origin_hash", "retry_at");
        assertThat(row.get("origin_hash").toString()).matches("[a-f0-9]{64}");
    }

    @Test void shorterSubsequentResponseNeverPullsTheSharedDeadlineForward() {
        first.onRateLimited(RateLimitedException.Service.AI, ENDPOINT, Instant.now().plusSeconds(500));
        Timestamp before = database.jdbc.queryForObject("SELECT retry_at FROM integration_cooldown", Timestamp.class);
        second.onRateLimited(RateLimitedException.Service.AI, ENDPOINT, Instant.now().plusSeconds(60));
        assertThat(database.jdbc.queryForObject("SELECT retry_at FROM integration_cooldown", Timestamp.class)).isEqualTo(before);
        first.onRateLimited(RateLimitedException.Service.AI, ENDPOINT, Instant.now().plusSeconds(800));
        assertThat(database.jdbc.queryForObject("SELECT retry_at FROM integration_cooldown", Timestamp.class)).isAfter(before);
    }

    @Test void expiredAdmissionResumesAndNextResponseRemovesOnlyExpiredRowsForThatService() {
        first.onRateLimited(RateLimitedException.Service.GIT, ENDPOINT, Instant.now().plusSeconds(300));
        first.onRateLimited(RateLimitedException.Service.AI, ENDPOINT, Instant.now().plusSeconds(300));
        database.jdbc.update("UPDATE integration_cooldown SET retry_at=? WHERE service='GIT'", Timestamp.from(Instant.now().minusSeconds(1)));
        second.beforeRequest(RateLimitedException.Service.GIT, ENDPOINT);
        second.onRateLimited(RateLimitedException.Service.GIT, URI.create("https://another.example.invalid/"), Instant.now().plusSeconds(300));
        assertThat(database.count("integration_cooldown")).isEqualTo(2);
        assertThat(database.count("integration_cooldown_guard")).isEqualTo(2);
        assertThatThrownBy(() -> second.beforeRequest(RateLimitedException.Service.AI, ENDPOINT)).isInstanceOf(RateLimitedException.class);
    }

    @Test void unavailableDatabaseDoesNotAuthorizeExternalIoOrEraseAnExistingDeadline() {
        first.onRateLimited(RateLimitedException.Service.GIT, ENDPOINT, Instant.now().plusSeconds(300));
        database.jdbc.execute("ALTER TABLE integration_cooldown RENAME TO fixture_unavailable_cooldown");
        assertThatThrownBy(() -> second.beforeRequest(RateLimitedException.Service.GIT, ENDPOINT)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> second.onRateLimited(RateLimitedException.Service.GIT, ENDPOINT, Instant.now().plusSeconds(600)))
                .isInstanceOf(DataAccessException.class);
        database.jdbc.execute("ALTER TABLE fixture_unavailable_cooldown RENAME TO integration_cooldown");
        assertThat(database.count("integration_cooldown")).isEqualTo(1);
        assertThatThrownBy(() -> second.beforeRequest(RateLimitedException.Service.GIT, ENDPOINT)).isInstanceOf(RateLimitedException.class);
    }

    @Test void unreasonableOrCredentialBearingOriginsAreRejectedBeforePersistence() {
        for (URI endpoint : new URI[] { URI.create("ftp://service.invalid/"), URI.create("https://user:password@service.invalid/"),
                URI.create("https://service.invalid:0/"), URI.create("https://service.invalid:65536/"), URI.create("/relative") }) {
            assertThatThrownBy(() -> first.beforeRequest(RateLimitedException.Service.GIT, endpoint)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> first.onRateLimited(RateLimitedException.Service.AI, ENDPOINT, Instant.now().plusSeconds(90000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(database.count("integration_cooldown")).isZero();
    }
}
