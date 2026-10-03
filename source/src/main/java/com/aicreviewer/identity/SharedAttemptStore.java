package com.aicreviewer.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared fixed-window quotas; only prehashed account/address keys reach storage. */
@Component
public final class SharedAttemptStore {
    private static final Pattern ACCOUNT_KEY = Pattern.compile("account:[0-9a-f]{64}");
    private static final Pattern ADDRESS_KEY = Pattern.compile("ip:[0-9a-f]{64}");
    private static final String[] SCOPES = {"LOGIN", "SIGNUP"};
    private static final System.Logger LOG = System.getLogger(SharedAttemptStore.class.getName());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<Instant> fixtureNow;
    private volatile String databaseNowSql;

    @Autowired
    public SharedAttemptStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, null);
    }

    /** The supplied clock is for deterministic fixtures; production always reads the DB clock. */
    SharedAttemptStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Supplier<Instant> now) {
        // This private template is not a bean, so the general JDBC postprocessor cannot
        // replace the short authentication timeout with the application's longer limit.
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(3);
        this.fixtureNow = now;
    }

    public LoginAttemptLimiter.Decision acquire(String scope, String accountKey, String addressKey,
                                                int accountLimit, int addressLimit, int windowSeconds, int maxEntries) {
        validateScope(scope);
        validateAccountKey(accountKey);
        if (addressKey == null || !ADDRESS_KEY.matcher(addressKey).matches()
                || accountLimit < 1 || addressLimit < 1 || windowSeconds < 1 || windowSeconds > 86_400
                || maxEntries < 2 || maxEntries > 1_000_000) {
            throw new IllegalArgumentException("Invalid shared authentication attempt policy or hashed key");
        }
        return transaction(() -> acquireLocked(scope, accountKey, addressKey,
                accountLimit, addressLimit, windowSeconds, maxEntries));
    }

    private LoginAttemptLimiter.Decision acquireLocked(String scope, String accountKey, String addressKey,
                                                       int accountLimit, int addressLimit, int windowSeconds, int maxEntries) {
        String previousPolicy = lockScope(scope);
        // PostgreSQL CURRENT_TIMESTAMP is fixed at transaction start. Read the actual
        // clock only after obtaining the guard, including time spent waiting for it.
        Instant now = databaseNow();
        deleteExpired(scope, now);
        String policy = fingerprint(accountLimit, addressLimit, windowSeconds, maxEntries);
        if (!policy.equals(previousPolicy)) {
            if (entryCount(scope) != 0) throw new AttemptStoreUnavailableException();
            requireOne(jdbc.update("UPDATE auth_attempt_policy SET policy_fingerprint=? WHERE scope=?", policy, scope));
        }

        Map<String, Bucket> buckets = new HashMap<>();
        jdbc.query("SELECT key_hash, attempt_count, expires_at FROM auth_attempt_bucket WHERE scope=? AND key_hash IN (?,?)",
                result -> {
                    buckets.put(result.getString("key_hash"),
                            new Bucket(result.getInt("attempt_count"), result.getTimestamp("expires_at").toInstant()));
                }, scope, accountKey, addressKey);
        Bucket account = buckets.get(accountKey);
        Bucket address = buckets.get(addressKey);
        if (account != null && account.count() >= accountLimit) return denied(account.expiresAt(), now);
        if (address != null && address.count() >= addressLimit) return denied(address.expiresAt(), now);

        int needed = (account == null ? 1 : 0) + (address == null ? 1 : 0);
        if (needed > 0 && entryCount(scope) + needed > maxEntries) {
            // Existing throttled accounts must never be evicted to admit new names.
            Instant earliest = jdbc.queryForObject("SELECT MIN(expires_at) FROM auth_attempt_bucket WHERE scope=?",
                    (result, row) -> {
                        Timestamp expiry = result.getTimestamp(1);
                        return expiry == null ? null : expiry.toInstant();
                    }, scope);
            if (earliest == null) throw new AttemptStoreUnavailableException();
            return denied(earliest, now);
        }

        increment(scope, accountKey, account, now.plusSeconds(windowSeconds));
        increment(scope, addressKey, address, now.plusSeconds(windowSeconds));
        return new LoginAttemptLimiter.Decision(true, 0);
    }

    /** Successful authentication clears only its account bucket, never its address quota. */
    public void clearAccount(String scope, String accountKey) {
        validateScope(scope);
        validateAccountKey(accountKey);
        transaction(() -> {
            lockScope(scope);
            jdbc.update("DELETE FROM auth_attempt_bucket WHERE scope=? AND key_hash=?", scope, accountKey);
            return Boolean.TRUE;
        });
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void purgeExpired() {
        for (String scope : SCOPES) {
            try {
                // Separate transactions avoid holding both guards during scheduled cleanup.
                transaction(() -> {
                    lockScope(scope);
                    deleteExpired(scope, databaseNow());
                    return Boolean.TRUE;
                });
            } catch (AttemptStoreUnavailableException unavailable) {
                LOG.log(System.Logger.Level.WARNING, "Shared authentication attempt cleanup failed.");
            }
        }
    }

    private String lockScope(String scope) {
        return jdbc.queryForObject("SELECT policy_fingerprint FROM auth_attempt_policy WHERE scope=? FOR UPDATE",
                String.class, scope);
    }

    private long entryCount(String scope) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM auth_attempt_bucket WHERE scope=?", Long.class, scope);
        if (count == null) throw new AttemptStoreUnavailableException();
        return count;
    }

    private void deleteExpired(String scope, Instant now) {
        jdbc.update("DELETE FROM auth_attempt_bucket WHERE scope=? AND expires_at<=?", scope, Timestamp.from(now));
    }

    private void increment(String scope, String key, Bucket current, Instant newExpiry) {
        if (current == null) {
            requireOne(jdbc.update("INSERT INTO auth_attempt_bucket(scope,key_hash,attempt_count,expires_at) VALUES (?,?,1,?)",
                    scope, key, Timestamp.from(newExpiry)));
        } else {
            requireOne(jdbc.update("UPDATE auth_attempt_bucket SET attempt_count=attempt_count+1 WHERE scope=? AND key_hash=?",
                    scope, key));
        }
    }

    private Instant databaseNow() {
        if (fixtureNow != null) return Objects.requireNonNull(fixtureNow.get());
        String query = databaseNowSql;
        if (query == null) {
            query = jdbc.execute((ConnectionCallback<String>) connection -> switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> "SELECT clock_timestamp()";
                case "H2" -> "SELECT CURRENT_TIMESTAMP";
                default -> throw new AttemptStoreUnavailableException();
            });
            if (query == null) throw new AttemptStoreUnavailableException();
            databaseNowSql = query;
        }
        Instant now = jdbc.queryForObject(query, (result, row) -> result.getTimestamp(1).toInstant());
        if (now == null) throw new AttemptStoreUnavailableException();
        return now;
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            T value = transactions.execute(status -> work.get());
            if (value == null) throw new AttemptStoreUnavailableException();
            return value;
        } catch (RuntimeException failure) {
            // Transaction/driver exceptions can expose SQL and arguments. Do not attach
            // their message or cause, including failures raised while committing.
            throw new AttemptStoreUnavailableException();
        }
    }

    private static String fingerprint(int accountLimit, int addressLimit, int windowSeconds, int maxEntries) {
        String canonical = "v1|" + accountLimit + '|' + addressLimit + '|' + windowSeconds + '|' + maxEntries;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static LoginAttemptLimiter.Decision denied(Instant expiry, Instant now) {
        Duration remaining = Duration.between(now, expiry);
        long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        return new LoginAttemptLimiter.Decision(false, Math.max(1, seconds));
    }

    private static void requireOne(int affected) {
        if (affected != 1) throw new AttemptStoreUnavailableException();
    }

    private static void validateScope(String scope) {
        if (!"LOGIN".equals(scope) && !"SIGNUP".equals(scope)) {
            throw new IllegalArgumentException("Invalid shared authentication attempt scope");
        }
    }

    private static void validateAccountKey(String accountKey) {
        if (accountKey == null || !ACCOUNT_KEY.matcher(accountKey).matches()) {
            throw new IllegalArgumentException("Invalid shared authentication attempt hashed account key");
        }
    }

    private record Bucket(int count, Instant expiresAt) { }
}
