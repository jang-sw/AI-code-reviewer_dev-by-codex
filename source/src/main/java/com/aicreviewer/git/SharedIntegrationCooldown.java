package com.aicreviewer.git;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared origin cooldown; admission already in progress cannot be recalled or made exactly once. */
@Component
public final class SharedIntegrationCooldown implements RateLimitGate {
    static final int MAX_ORIGINS_PER_SERVICE = 10000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile String nowSql;

    public SharedIntegrationCooldown(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(3);
    }

    @Override public void beforeRequest(RateLimitedException.Service service, URI endpoint) {
        Objects.requireNonNull(service);
        String key = originHash(endpoint);
        Instant retryAt = transactions.execute(status -> {
            Instant now = databaseNow();
            var matches = jdbc.query("SELECT retry_at FROM integration_cooldown WHERE service=? AND origin_hash=? AND retry_at>?",
                    (rs, row) -> rs.getTimestamp(1).toInstant(), service.name(), key, Timestamp.from(now));
            return matches.isEmpty() ? null : matches.getFirst();
        });
        if (retryAt != null) throw new RateLimitedException(service, retryAt, false);
    }

    @Override public void onRateLimited(RateLimitedException.Service service, URI endpoint, Instant retryAt) {
        Objects.requireNonNull(service);
        Objects.requireNonNull(retryAt);
        String key = originHash(endpoint);
        transactions.executeWithoutResult(status -> {
            // Scope guard serializes missing-row insertion, max updates and bounded expiry cleanup.
            String guard = jdbc.queryForObject("SELECT service FROM integration_cooldown_guard WHERE service=? FOR UPDATE",
                    String.class, service.name());
            if (!service.name().equals(guard)) throw new IllegalStateException("External service cooldown guard unavailable");
            Instant now = databaseNow();
            Instant until = retryAt.isAfter(now) ? retryAt : now.plusSeconds(30);
            // Only the bounded transport policy may supply an automatically retriable deadline.
            if (until.isAfter(now.plusSeconds(86430))) throw new IllegalArgumentException("External service cooldown exceeds its time bound");
            jdbc.update("DELETE FROM integration_cooldown WHERE service=? AND retry_at<=?", service.name(), Timestamp.from(now));
            var existing = jdbc.query("SELECT retry_at FROM integration_cooldown WHERE service=? AND origin_hash=?",
                    (rs, row) -> rs.getTimestamp(1).toInstant(), service.name(), key);
            if (existing.isEmpty()) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM integration_cooldown WHERE service=?", Long.class, service.name());
                if (count == null || count >= MAX_ORIGINS_PER_SERVICE) {
                    throw new IntegrationException("External service cooldown capacity exceeded");
                }
                jdbc.update("INSERT INTO integration_cooldown(service,origin_hash,retry_at) VALUES (?,?,?)",
                        service.name(), key, Timestamp.from(until));
            } else if (until.isAfter(existing.getFirst())) {
                jdbc.update("UPDATE integration_cooldown SET retry_at=? WHERE service=? AND origin_hash=?",
                        Timestamp.from(until), service.name(), key);
            }
        });
    }

    private Instant databaseNow() {
        String query = nowSql;
        if (query == null) {
            query = jdbc.execute((ConnectionCallback<String>) connection -> switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> "SELECT clock_timestamp()";
                case "H2" -> "SELECT CURRENT_TIMESTAMP";
                default -> throw new IllegalStateException("Unsupported external service cooldown database");
            });
            nowSql = Objects.requireNonNull(query);
        }
        return Objects.requireNonNull(jdbc.queryForObject(query, (rs, row) -> rs.getTimestamp(1).toInstant()));
    }

    static String originHash(URI endpoint) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid external service cooldown origin");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("Invalid external service cooldown origin");
        int port = endpoint.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : endpoint.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid external service cooldown origin");
        String canonical = scheme + "://" + endpoint.getHost().toLowerCase(Locale.ROOT) + ':' + port;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
