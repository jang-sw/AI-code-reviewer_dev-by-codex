package com.aicreviewer.operations;

import com.aicreviewer.identity.AccountUserDetailsService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.enabled=false", "app.review.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MonitoringSecurityTest {
    private static final String[] ROUTES = {"/admin/monitoring", "/admin/monitoring/snapshot"};
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;
    @MockitoBean OperationsTelemetryService telemetry;

    @BeforeEach void setup() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES('monitor-admin','unused','monitor-admin','ADMIN'),('monitor-member','unused','monitor-member','USER')");
        when(telemetry.observation()).thenReturn(observation("READY"));
    }

    @Test void anonymousAndOrdinaryUsersCannotReadEitherRepresentation() throws Exception {
        for (String route : ROUTES) {
            mvc.perform(get(route)).andExpect(status().is3xxRedirection());
            mvc.perform(get(route).with(user(accounts.loadUserByUsername("monitor-member"))))
                    .andExpect(status().isForbidden());
        }
        verify(telemetry, never()).observation();
    }

    @Test void staleAdminPrincipalLosesAccessAfterDisablingOrRoleChange() throws Exception {
        var principal = accounts.loadUserByUsername("monitor-admin");
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='monitor-admin'");
        for (String route : ROUTES) {
            mvc.perform(get(route).with(user(principal))).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?expired"));
        }
        jdbc.update("UPDATE app_user SET enabled=TRUE,role='USER' WHERE username='monitor-admin'");
        for (String route : ROUTES) {
            mvc.perform(get(route).with(user(principal))).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?expired"));
        }
        verify(telemetry, never()).observation();
    }

    @Test void adminAuthorityAloneCannotBypassTheCurrentDatabaseRole() throws Exception {
        for (String route : ROUTES) {
            mvc.perform(get(route).with(user("monitor-member").roles("ADMIN")))
                    .andExpect(status().isForbidden());
        }
        verify(telemetry, never()).observation();
    }

    @ParameterizedTest @ValueSource(strings = {"STARTING", "READY", "FAILED", "STALE"})
    void pageAlwaysExplainsObservationStateWhileJsonIsUnavailableUnlessReady(String state) throws Exception {
        when(telemetry.observation()).thenReturn(observation(state));
        var principal = accounts.loadUserByUsername("monitor-admin");
        var page = mvc.perform(get("/admin/monitoring").with(user(principal)))
                .andExpect(status().isOk()).andExpect(view().name("admin/monitoring"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(model().attribute("pageTitle", "서버 상태")).andReturn();
        var snapshot = (MonitoringSnapshot) page.getModelAndView().getModel().get("monitoring");
        assertThat(snapshot.status()).isEqualTo(state);
        assertThat(snapshot.available()).isEqualTo(state.equals("READY"));
        assertThat(snapshot.currentServerWorkerEnabled()).isFalse();
        assertThat(snapshot.currentServerSchedulerEnabled()).isFalse();
        var json = mvc.perform(get("/admin/monitoring/snapshot").with(user(principal)))
                .andExpect(status().is(state.equals("READY") ? 200 : 503))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(state))
                .andExpect(jsonPath("$.available").value(state.equals("READY")))
                .andExpect(jsonPath("$.currentServerWorkerEnabled").value(false))
                .andExpect(jsonPath("$.currentServerSchedulerEnabled").value(false));
        if (state.equals("READY")) {
            json.andExpect(jsonPath("$.projectCounts.APPROVED").value(4))
                    .andExpect(jsonPath("$.requestCounts.RUNNING").value(2))
                    .andExpect(jsonPath("$.latestFailedProjects").value(3));
        } else {
            json.andExpect(jsonPath("$.projectCounts").isEmpty()).andExpect(jsonPath("$.requestCounts").isEmpty())
                    .andExpect(jsonPath("$.latestFailedProjects").doesNotExist())
                    .andExpect(jsonPath("$.delayedActiveRequests").doesNotExist());
        }
        assertThat(json.andReturn().getResponse().getContentAsString())
                .doesNotContain("monitor-admin", "monitor-member", "claim_token", "claimToken", "request_id", "requestId", "password", "https://", "apiKey");
        verify(telemetry, times(2)).observation();
    }

    @Test void getOnlySurfaceDoesNotTriggerMutationsOrCollection() throws Exception {
        var principal = accounts.loadUserByUsername("monitor-admin");
        for (String route : ROUTES) {
            mvc.perform(post(route).with(user(principal)).with(csrf())).andExpect(status().isMethodNotAllowed());
        }
        verify(telemetry, never()).observation();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_run", Long.class)).isZero();
    }

    private static OperationsTelemetryService.Observation observation(String state) {
        return new OperationsTelemetryService.Observation(state, Instant.parse("2026-09-26T22:00:00Z"),
                Instant.parse("2026-09-26T22:00:30Z"), 30L, 120,
                Map.of("PENDING", 1L, "APPROVED", 4L, "REJECTED", 0L, "PAUSED", 2L),
                Map.of("QUEUED", 3L, "RUNNING", 2L, "SUCCEEDED", 1L, "FAILED", 1L, "CANCELLED", 0L),
                Instant.parse("2026-09-26T20:00:00Z"), 1L, 3L);
    }
}
