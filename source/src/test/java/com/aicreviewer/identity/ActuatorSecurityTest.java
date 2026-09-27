package com.aicreviewer.identity;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitRepositoryClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full security chain and Boot's actual health/metrics endpoint mappings; all network clients are inert. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:actuator_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.worker-enabled=false"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
@Transactional
class ActuatorSecurityTest {
    private static final String PASSWORD = "Synthetic-actuator-login-5281!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String[] ADMIN_ROUTES = {
            "/actuator/health", "/actuator/metrics", "/actuator/metrics/ai.reviewer.security.test.requests"
    };

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired HealthContributorRegistry contributors;
    @Autowired HealthEndpointGroups groups;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean AccountUserDetailsService accounts;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    private Counter counter;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES('probe-admin',?,'probe-admin','ADMIN'),('probe-user',?,'probe-user','USER')", HASH, HASH);
        counter = Counter.builder("ai.reviewer.security.test.requests").register(meters);
        counter.increment();
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @AfterEach
    void restoreAvailabilityAndCheckNoExternalCalls() {
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        meters.remove(counter);
        verifyNoInteractions(git, ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"liveness", "readiness"})
    void anonymousGetAndHeadExposeOnlyStatus(String group) throws Exception {
        String route = "/actuator/health/" + group;
        mvc.perform(get(route).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        // MockMvc exposes the handler's HEAD body before Tomcat suppresses it. The real HTTP
        // ApplicationPostgresTest separately verifies an empty HEAD response body on the wire.
        mvc.perform(head(route)).andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
        verify(accounts, never()).loadUserByUsername(anyString());
    }

    @Test
    void onlyExplicitApplicationAndDatabaseContributorsBelongToThePublicGroups() {
        assertThat(groups.getNames()).containsExactlyInAnyOrder("liveness", "readiness");
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        for (String group : new String[] {"liveness", "readiness"}) {
            for (String other : new String[] {"diskSpace", "git", "ai", "mail", "custom"}) {
                assertThat(groups.get(group).isMember(other)).isFalse();
            }
        }
        assertThat(contributors.getContributor("db")).isNotNull();
    }

    @Test
    void databaseConnectionFailureMakesOnlyReadinessUnavailableWithoutExposingDetails(CapturedOutput output) throws Exception {
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("synthetic-private-db-host-and-account"));
        HealthContributor original = contributors.unregisterContributor("db");
        assertThat(original).isNotNull();
        contributors.registerContributor("db", new DataSourceHealthIndicator(unavailable));
        try {
            mvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
            verifyNoInteractions(unavailable);
            mvc.perform(get("/actuator/health/readiness").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().string("{\"status\":\"DOWN\"}"));
            mvc.perform(head("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable()).andExpect(content().string("{\"status\":\"DOWN\"}"));
            verify(unavailable, atLeastOnce()).getConnection();
            assertThat(output.getAll()).doesNotContain("synthetic-private-db-host-and-account", "DataSource health check failed");
        } finally {
            contributors.unregisterContributor("db");
            contributors.registerContributor("db", original);
        }
    }

    @Test
    void readinessAvailabilityCanRefuseTrafficWithoutBreakingLiveness() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string("{\"status\":\"OUT_OF_SERVICE\"}"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
    }

    @Test
    void brokenLivenessIsReportedWithoutDetails() throws Exception {
        AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string("{\"status\":\"DOWN\"}"));
    }

    @Test
    void incidentalAuthenticationDoesNotMakePublicProbesDependOnTheAccountDatabase() throws Exception {
        AccountPrincipal oldAdministrator = accounts.loadUserByUsername("probe-admin");
        jdbc.update("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE username='probe-admin'");
        clearInvocations(accounts);
        for (String group : new String[] {"liveness", "readiness"}) {
            mvc.perform(get("/actuator/health/" + group).with(user(oldAdministrator)))
                    .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
            mvc.perform(head("/actuator/health/" + group).with(user(oldAdministrator)))
                    .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
        }
        verify(accounts, never()).loadUserByUsername(anyString());
        mvc.perform(get("/actuator/metrics").with(user(oldAdministrator)))
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void publicProbeMatcherHandlesTheApplicationContextPath() throws Exception {
        mvc.perform(get("/reviewer/actuator/health/liveness").contextPath("/reviewer"))
                .andExpect(status().isOk()).andExpect(content().string("{\"status\":\"UP\"}"));
        mvc.perform(get("/reviewer/actuator/metrics").contextPath("/reviewer"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/reviewer/login"));
    }

    @Test
    void anonymousAdministratorEndpointsRedirectToLoginAndRegularUsersAreForbidden() throws Exception {
        for (String route : ADMIN_ROUTES) {
            mvc.perform(get(route)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
            mvc.perform(head(route)).andExpect(status().isFound());
            mvc.perform(get(route).with(user(accounts.loadUserByUsername("probe-user"))))
                    .andExpect(status().isForbidden());
            mvc.perform(head(route).with(user(accounts.loadUserByUsername("probe-user"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void currentApprovedEnabledAdministratorCanReadHealthAndMetrics() throws Exception {
        var admin = accounts.loadUserByUsername("probe-admin");
        mvc.perform(get("/actuator/health").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/metrics").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.names", org.hamcrest.Matchers.hasItem(counter.getId().getName())));
        mvc.perform(get("/actuator/metrics/" + counter.getId().getName()).with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(counter.getId().getName()))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(head("/actuator/health").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(head("/actuator/metrics").with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.names", org.hamcrest.Matchers.hasItem(counter.getId().getName())));
        mvc.perform(head("/actuator/metrics/" + counter.getId().getName()).with(user(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(counter.getId().getName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "demoted", "pending", "version"})
    void existingAdministratorSessionIsRevokedImmediatelyAfterAnAccountChange(String change) throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("probe-admin").password(PASSWORD))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession(false);
        switch (change) {
            case "disabled" -> jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='probe-admin'");
            case "demoted" -> jdbc.update("UPDATE app_user SET role='USER' WHERE username='probe-admin'");
            case "pending" -> jdbc.update("UPDATE app_user SET role='USER',enabled=FALSE,approval_status='PENDING' WHERE username='probe-admin'");
            case "version" -> jdbc.update("UPDATE app_user SET security_version=security_version+1 WHERE username='probe-admin'");
            default -> throw new AssertionError(change);
        }
        mvc.perform(get("/actuator/metrics").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void unsafeRequestsAreForbiddenEvenWithAdministratorAndValidCsrf() throws Exception {
        var admin = accounts.loadUserByUsername("probe-admin");
        for (String route : new String[] {"/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health", "/actuator/metrics"}) {
            mvc.perform(post(route)).andExpect(status().isForbidden());
            mvc.perform(post(route).with(user(admin))).andExpect(status().isForbidden());
            for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {post(route), put(route), patch(route), delete(route)}) {
                mvc.perform(request.with(user(admin)).with(csrf())).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void otherActuatorRoutesAndNestedProbeDetailsStayInaccessibleEvenToAnAdministrator() throws Exception {
        var admin = accounts.loadUserByUsername("probe-admin");
        for (String route : new String[] {"/actuator", "/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/actuator/loggers",
                "/actuator/health/db", "/actuator/health/readiness/db", "/actuator/health/liveness/livenessState", "/actuator/health/liveness/"}) {
            mvc.perform(get(route).with(user(admin))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/actuator/health/readiness/db")).andExpect(status().isFound());
        mvc.perform(options("/actuator/health/liveness").with(user(admin))).andExpect(status().isForbidden());
    }
}
