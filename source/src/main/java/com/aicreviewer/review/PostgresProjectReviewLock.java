package com.aicreviewer.review;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PostgresProjectReviewLock implements ProjectReviewLock {
    private static final long KEY_NAMESPACE = 0x4149435200000000L;
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public PostgresProjectReviewLock(DataSource dataSource) {
        this(dataSource, 30);
    }

    @Autowired
    public PostgresProjectReviewLock(DataSource dataSource, @Value("${app.jdbc.query-timeout-seconds:30}") int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("JDBC query timeout must be between 1 and 3600 seconds");
        }
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public Optional<Lease> tryAcquire(long projectId) {
        // Never use DataSourceUtils here: a transaction-bound connection may return to the
        // pool before external calls finish. This session owns the lock for the whole run.
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            long key = KEY_NAMESPACE ^ projectId;
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setLong(1, key);
                try (var result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) {
                        return Optional.of(new SessionLease(connection, key, queryTimeoutSeconds));
                    }
                }
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException exception) {
            // A failed response can leave acquisition uncertain; discard the physical session.
            if (connection != null) {
                try { connection.abort(Runnable::run); } catch (SQLException ignored) { /* Close next. */ }
            }
            closeQuietly(connection);
            throw new IllegalStateException("Cannot acquire project review lock", exception);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { /* Connection failure. */ }
        }
    }

    private static final class SessionLease implements Lease {
        private final Connection connection;
        private final long key;
        private final int queryTimeoutSeconds;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SessionLease(Connection connection, long key, int queryTimeoutSeconds) {
            this.connection = connection;
            this.key = key;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            SQLException failure = null;
            try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setLong(1, key);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new SQLException("Project review lock was not held");
                    }
                }
            } catch (SQLException exception) {
                failure = exception;
                // Do not return a session with an unreleased lock to the pool.
                try { connection.abort(Runnable::run); } catch (SQLException abortFailure) {
                    exception.addSuppressed(abortFailure);
                }
            } finally {
                try { connection.close(); } catch (SQLException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw new IllegalStateException("Cannot release project review lock", failure);
        }
    }
}
