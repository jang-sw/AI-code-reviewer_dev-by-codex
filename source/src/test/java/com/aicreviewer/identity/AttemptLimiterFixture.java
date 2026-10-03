package com.aicreviewer.identity;

import java.time.Clock;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Fast tests use the real store and migration, with a deterministic clock only in this fixture. */
final class AttemptLimiterFixture implements AutoCloseable {
    final JdbcTemplate jdbc;
    final SharedAttemptStore store;
    private final SingleConnectionDataSource source;

    AttemptLimiterFixture(Clock clock) {
        source = new SingleConnectionDataSource("jdbc:h2:mem:attempt_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__shared_auth_attempt_limits.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        store = new SharedAttemptStore(jdbc, new DataSourceTransactionManager(source), clock::instant);
    }

    LoginAttemptLimiter login(int account, int address, int seconds, int capacity) {
        return new LoginAttemptLimiter(store, account, address, seconds, capacity);
    }

    SignupAttemptLimiter signup(int attempts, int seconds, int capacity) {
        return new SignupAttemptLimiter(store, attempts, seconds, capacity);
    }

    int entries(String scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_attempt_bucket WHERE scope=?", Integer.class, scope);
    }

    @Override public void close() { source.destroy(); }
}
