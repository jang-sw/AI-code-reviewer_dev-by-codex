package com.aicreviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zaxxer.hikari.HikariDataSource;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JdbcSocketTimeoutTest {
    @Test
    void boundsUnresponsivePostgresHandshakeReadsOnLoopback() throws Exception {
        // No real database, external service or credentials. The peer accepts but never replies.
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var source = new HikariDataSource()) {
            server.setSoTimeout(5000);
            var executor = Executors.newSingleThreadExecutor();
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    return socket.getInputStream().readAllBytes().length;
                }
            });
            try {
                source.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + server.getLocalPort()
                        + "/socket_timeout_fixture?sslmode=disable");
                JdbcTimeoutConfiguration.jdbcTimeoutPostProcessor(new MockEnvironment()
                        .withProperty("app.jdbc.query-timeout-seconds", "1")
                        .withProperty("app.jdbc.socket-timeout-seconds", "2")
                        .withProperty("app.jdbc.connect-timeout-seconds", "1"))
                        .postProcessBeforeInitialization(source, "dataSource");
                Properties settings = new Properties();
                settings.putAll(source.getDataSourceProperties());
                settings.setProperty("user", "socket_fixture");
                settings.setProperty("password", "");

                long started = System.nanoTime();
                Throwable failure = catchThrowable(() -> {
                    try (var ignored = DriverManager.getConnection(source.getJdbcUrl(), settings)) {
                        throw new AssertionError("An unresponsive peer must not establish a connection");
                    }
                });
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                assertThat(failure).isInstanceOf(SQLException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);
                assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
                assertThat(peer.get(5, TimeUnit.SECONDS)).isPositive();
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
