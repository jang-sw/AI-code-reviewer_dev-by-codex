package com.aicreviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.support.TestPropertySourceUtils;

class JdbcTimeoutConfigurationTest {
    @Test
    void bindsPropertiesBeforeAddingLimitsWithoutStartingPool() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "fixture.datasource.jdbc-url=jdbc:postgresql://127.0.0.1:1/timeout_fixture",
                    "app.jdbc.query-timeout-seconds=2", "app.jdbc.socket-timeout-seconds=4",
                    "app.jdbc.connect-timeout-seconds=1");
            context.register(JdbcTimeoutConfiguration.class, BoundDataSourceConfiguration.class);
            context.refresh();

            HikariDataSource source = context.getBean(HikariDataSource.class);
            assertThat(source.getJdbcUrl()).startsWith("jdbc:postgresql:");
            assertThat(source.getDataSourceProperties()).containsEntry("connectTimeout", "1")
                    .containsEntry("socketTimeout", "4");
            assertThat(source.getHikariPoolMXBean()).isNull();
            assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout()).isEqualTo(2);
        }
    }

    @Test
    void h2DoesNotReceivePostgresDriverPropertiesButStatementsRemainBounded() {
        var processor = processor(new MockEnvironment());
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:timeout_fixture");
            processor.postProcessBeforeInitialization(source, "dataSource");
            assertThat(source.getDataSourceProperties()).doesNotContainKeys("socketTimeout", "connectTimeout");
        }
        var jdbc = new JdbcTemplate();
        processor.postProcessBeforeInitialization(jdbc, "jdbcTemplate");
        assertThat(jdbc.getQueryTimeout()).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(strings = { "socketTimeout=0", "connectTimeout=0", "socketTimeout=44",
            "socketTimeout=45&socketTimeout=45", "socket%54imeout=0", "socketTimeout=%broken" })
    void rejectsUrlTimeoutOverridesWithoutEchoingConnectionSecrets(String query) {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/private_database?password=do-not-echo&" + query);
            assertThatThrownBy(() -> processor(new MockEnvironment()).postProcessBeforeInitialization(source, "dataSource"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("PostgreSQL URL timeouts must match the configured JDBC timeouts")
                    .hasNoCause();
            assertThat(source.getHikariPoolMXBean()).isNull();
        }
    }

    @Test
    void matchingUrlTimeoutsAreAccepted() {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/timeout_fixture?socketTimeout=45&connectTimeout=10");
            processor(new MockEnvironment()).postProcessBeforeInitialization(source, "dataSource");
            assertThat(source.getDataSourceProperties()).containsEntry("socketTimeout", "45")
                    .containsEntry("connectTimeout", "10");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "3601", "not-a-number-secret", "1.5" })
    void invalidSettingsFailWithoutIncludingValues(String value) {
        for (String key : new String[] { "query", "socket", "connect" }) {
            assertThatThrownBy(() -> processor(new MockEnvironment()
                    .withProperty("app.jdbc." + key + "-timeout-seconds", value)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("JDBC timeouts must be whole seconds between 1 and 3600")
                    .hasNoCause();
        }
    }

    @Test
    void socketTimeoutMustLeaveTimeForQueryCancellation() {
        assertThatThrownBy(() -> processor(new MockEnvironment()
                .withProperty("app.jdbc.query-timeout-seconds", "45")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JDBC socket timeout must exceed the query timeout");
    }

    private static BeanPostProcessor processor(MockEnvironment environment) {
        return JdbcTimeoutConfiguration.jdbcTimeoutPostProcessor(environment);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class BoundDataSourceConfiguration {
        @Bean
        @ConfigurationProperties("fixture.datasource")
        HikariDataSource dataSource() { return new HikariDataSource(); }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource source) { return new JdbcTemplate(source); }
    }
}
