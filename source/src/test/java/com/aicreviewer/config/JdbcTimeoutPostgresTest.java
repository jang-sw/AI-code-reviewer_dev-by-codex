package com.aicreviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Read-only cancellation probe, restricted to the script-created local integration database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class JdbcTimeoutPostgresTest {
    @Test
    void cancelsLongQueryAndKeepsConnectionUsable() {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
            source.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
            source.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
            source.setMinimumIdle(0);
            source.setMaximumPoolSize(1);
            source.setConnectionTimeout(2000);
            var processor = JdbcTimeoutConfiguration.jdbcTimeoutPostProcessor(new MockEnvironment()
                    .withProperty("app.jdbc.query-timeout-seconds", "1")
                    .withProperty("app.jdbc.socket-timeout-seconds", "3")
                    .withProperty("app.jdbc.connect-timeout-seconds", "1"));
            processor.postProcessBeforeInitialization(source, "dataSource");
            var jdbc = new JdbcTemplate(source);
            processor.postProcessBeforeInitialization(jdbc, "jdbcTemplate");
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

            long started = System.nanoTime();
            Throwable failure = catchThrowable(() -> jdbc.execute("SELECT pg_sleep(10)"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(failure).isInstanceOf(DataAccessException.class);
            assertThat(((DataAccessException) failure).getMostSpecificCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) ((DataAccessException) failure).getMostSpecificCause()).getSQLState())
                    .isEqualTo("57014"); // PostgreSQL query_canceled, rather than a lost socket/session.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
    }
}
