package com.aicreviewer.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bound database reads as well as pool acquisition; never include connection settings in errors. */
@Configuration(proxyBeanMethods = false)
public class JdbcTimeoutConfiguration {
    @Bean
    static BeanPostProcessor jdbcTimeoutPostProcessor(Environment environment) {
        return new TimeoutPostProcessor(seconds(environment, "app.jdbc.query-timeout-seconds", 30),
                seconds(environment, "app.jdbc.socket-timeout-seconds", 45),
                seconds(environment, "app.jdbc.connect-timeout-seconds", 10));
    }

    private static int seconds(Environment environment, String key, int fallback) {
        try {
            int value = environment.getProperty(key, Integer.class, fallback);
            if (value < 1 || value > 3600) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JDBC timeouts must be whole seconds between 1 and 3600");
        }
    }

    static final class TimeoutPostProcessor implements BeanPostProcessor, Ordered {
        private final int querySeconds;
        private final int socketSeconds;
        private final int connectSeconds;

        TimeoutPostProcessor(int querySeconds, int socketSeconds, int connectSeconds) {
            if (socketSeconds <= querySeconds) {
                throw new IllegalArgumentException("JDBC socket timeout must exceed the query timeout");
            }
            this.querySeconds = querySeconds;
            this.socketSeconds = socketSeconds;
            this.connectSeconds = connectSeconds;
        }

        @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof JdbcTemplate jdbc) jdbc.setQueryTimeout(querySeconds);
            if (bean instanceof HikariDataSource source && source.getJdbcUrl() != null
                    && source.getJdbcUrl().startsWith("jdbc:postgresql:")) {
                // ConfigurationProperties binding has already run, but consumers (including Flyway)
                // have not received this bean. A started pool can no longer be safely reconfigured.
                if (source.getHikariPoolMXBean() != null) {
                    throw new IllegalStateException("PostgreSQL timeout configuration requires an unstarted connection pool");
                }
                validateUrlTimeouts(source.getJdbcUrl());
                source.addDataSourceProperty("connectTimeout", Integer.toString(connectSeconds));
                source.addDataSourceProperty("socketTimeout", Integer.toString(socketSeconds));
            }
            return bean;
        }

        private void validateUrlTimeouts(String url) {
            int question = url.indexOf('?');
            if (question < 0) return;
            var seen = new HashSet<String>();
            try {
                for (String part : url.substring(question + 1).split("&")) {
                    String[] pair = part.split("=", 2);
                    String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    if (!key.equals("connectTimeout") && !key.equals("socketTimeout")) continue;
                    String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                    int expected = key.equals("connectTimeout") ? connectSeconds : socketSeconds;
                    // pgJDBC gives URL properties precedence over driver Properties. Reject a
                    // conflicting/disabled value instead of appearing to enforce an ignored limit.
                    if (!seen.add(key) || !value.equals(Integer.toString(expected))) throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("PostgreSQL URL timeouts must match the configured JDBC timeouts");
            }
        }
    }
}
