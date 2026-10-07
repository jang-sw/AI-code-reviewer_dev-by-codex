package com.aicreviewer.web;

import com.aicreviewer.identity.AccountUserDetailsService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:request_page_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.worker-enabled=false", "app.review.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReviewRequestPageSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;
    private long projectId;
    private String requestId;

    @BeforeEach void fixture() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES('queue-owner','unused','queue-owner','USER'),('queue-admin','unused','queue-admin','ADMIN'),('queue-other','unused','queue-other','USER')");
        Long owner = jdbc.queryForObject("SELECT id FROM app_user WHERE username='queue-owner'", Long.class);
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES('Queue <script>fixture</script>','https://github.com/org/queue-page','GITHUB','github.com','org/queue-page',?,'APPROVED')", owner);
        projectId = jdbc.queryForObject("SELECT id FROM project WHERE owner_id=?", Long.class, owner);
        requestId = UUID.randomUUID().toString();
        Timestamp requested = Timestamp.from(Instant.now().minusSeconds(120));
        jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at) VALUES(?,?,'QUEUED','MANUAL',?,?,?)", projectId, requestId, owner, requested, requested);
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"})
    void ownerAndAdminReadAllDurableStatesWithWorkerPauseModel(String state) throws Exception {
        if (state.equals("RUNNING")) {
            jdbc.update("INSERT INTO review_run(project_id,status) VALUES(?,'RUNNING')", projectId);
            Long runId = jdbc.queryForObject("SELECT id FROM review_run WHERE project_id=?", Long.class, projectId);
            jdbc.update("UPDATE review_request SET state='RUNNING',claim_token='private-claim-fixture',run_id=?,attempt_count=2,last_attempt_at=CURRENT_TIMESTAMP WHERE project_id=?", runId, projectId);
        } else if (!state.equals("QUEUED")) {
            jdbc.update("UPDATE review_request SET state=?,finished_at=CURRENT_TIMESTAMP,result_code=? WHERE project_id=?", state,
                    state.equals("CANCELLED") ? "PROJECT_INELIGIBLE" : state.equals("FAILED") ? "REVIEW_FAILED" : "BATCH_COMPLETED", projectId);
        }
        for (String username : new String[] {"queue-owner", "queue-admin"}) {
            for (String path : new String[] {"/projects/" + projectId, "/reviews?projectId=" + projectId}) {
                var response = mvc.perform(get(path).with(user(accounts.loadUserByUsername(username))))
                        .andExpect(status().isOk()).andExpect(model().attribute("reviewWorkerEnabled", false))
                        .andExpect(model().attribute("scheduledReviewEnabled", false)).andReturn();
                var snapshot = (ReviewRequestView) response.getModelAndView().getModel().get("reviewRequest");
                assertThat(snapshot.state()).isEqualTo(state);
                assertThat(snapshot.active()).isEqualTo(state.equals("QUEUED") || state.equals("RUNNING"));
                assertThat(snapshot.toString()).doesNotContain(requestId, "private-claim-fixture", "requestedBy", "claim_token");
            }
        }
    }

    @Test void unrelatedAndAnonymousAccountsCannotReadEitherRequestPage() throws Exception {
        mvc.perform(get("/projects/" + projectId)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/reviews").param("projectId", Long.toString(projectId))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/projects/" + projectId).with(user(accounts.loadUserByUsername("queue-other")))).andExpect(status().isNotFound());
        mvc.perform(get("/reviews").param("projectId", Long.toString(projectId)).with(user(accounts.loadUserByUsername("queue-other")))).andExpect(status().isForbidden());
    }

    @Test void cancellationRemainsVisibleAfterReapprovalAndNoReadRequeuesIt() throws Exception {
        jdbc.update("UPDATE project SET status='PAUSED' WHERE id=?", projectId);
        jdbc.update("UPDATE review_request SET state='CANCELLED',finished_at=CURRENT_TIMESTAMP,result_code='PROJECT_INELIGIBLE' WHERE project_id=?", projectId);
        for (String status : new String[] {"PAUSED", "APPROVED"}) {
            jdbc.update("UPDATE project SET status=? WHERE id=?", status, projectId);
            var response = mvc.perform(get("/projects/" + projectId).with(user(accounts.loadUserByUsername("queue-owner"))))
                    .andExpect(status().isOk()).andReturn();
            var snapshot = (ReviewRequestView) response.getModelAndView().getModel().get("reviewRequest");
            assertThat(snapshot.state()).isEqualTo("CANCELLED");
            assertThat(snapshot.active()).isFalse();
            assertThat(snapshot.resultLabel()).contains("프로젝트가 승인 상태가 아니어서");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void requestPostStillRequiresCsrfEvenWhenWorkerIsPaused() throws Exception {
        mvc.perform(post("/projects/" + projectId + "/review").with(user(accounts.loadUserByUsername("queue-owner"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT request_id FROM review_request WHERE project_id=?", String.class, projectId)).isEqualTo(requestId);
    }

    @ParameterizedTest @ValueSource(strings = {"GIT_RATE_LIMITED", "AI_RATE_LIMITED"})
    void rateLimitWaitIsVisibleOnlyToOwnerAndAdminWithoutRequeueOrSourceMetadata(String code) throws Exception {
        Instant retryAt = Instant.parse("2026-01-01T03:04:05Z"); // Already due; claim, not the UI clock, ends waiting.
        jdbc.update("UPDATE review_request SET result_code=?,available_at=?,attempt_count=2 WHERE project_id=?",
                code, Timestamp.from(retryAt), projectId);
        for (String username : new String[] {"queue-owner", "queue-admin"}) {
            for (String path : new String[] {"/projects/" + projectId, "/reviews?projectId=" + projectId}) {
                var response = mvc.perform(get(path).with(user(accounts.loadUserByUsername(username))))
                        .andExpect(status().isOk()).andReturn();
                var snapshot = (ReviewRequestView) response.getModelAndView().getModel().get("reviewRequest");
                assertThat(snapshot.state()).isEqualTo("QUEUED");
                assertThat(snapshot.active()).isTrue();
                assertThat(snapshot.rateLimited()).isTrue();
                assertThat(snapshot.retryAt()).isEqualTo(retryAt);
                assertThat(snapshot.retryAtLabel()).isEqualTo("2026-01-01 03:04:05");
                assertThat(snapshot.rateLimitLabel()).startsWith(code.equals("GIT_RATE_LIMITED") ? "Git 서버" : "AI 서비스");
                assertThat(snapshot.toString()).doesNotContain(requestId, "claim_token", "requestedBy", "github.com", code);
                assertThat(response.getModelAndView().getModel()).doesNotContainKey("reviewProgress");
            }
        }
        mvc.perform(get("/projects/" + projectId).with(user(accounts.loadUserByUsername("queue-other")))).andExpect(status().isNotFound());
        mvc.perform(get("/reviews").param("projectId", Long.toString(projectId)).with(user(accounts.loadUserByUsername("queue-other"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT result_code FROM review_request WHERE project_id=?", String.class, projectId)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void terminalRateLimitFailureExplainsManualActionWithoutInventingRetryTime() throws Exception {
        jdbc.update("UPDATE review_request SET state='FAILED',result_code='RATE_LIMIT_EXHAUSTED',finished_at=CURRENT_TIMESTAMP WHERE project_id=?", projectId);
        for (String path : new String[] {"/projects/" + projectId, "/reviews?projectId=" + projectId}) {
            var response = mvc.perform(get(path).with(user(accounts.loadUserByUsername("queue-owner"))))
                    .andExpect(status().isOk()).andReturn();
            var snapshot = (ReviewRequestView) response.getModelAndView().getModel().get("reviewRequest");
            assertThat(snapshot.active()).isFalse();
            assertThat(snapshot.rateLimited()).isFalse();
            assertThat(snapshot.rateLimitExhausted()).isTrue();
            assertThat(snapshot.retryAt()).isNull();
            assertThat(snapshot.resultLabel()).contains("서비스 상태를 확인한 후 직접 다시 요청", "새 예약으로 자동 재접수하지 않습니다");
        }
        assertThat(jdbc.queryForObject("SELECT state FROM review_request WHERE project_id=?", String.class, projectId)).isEqualTo("FAILED");
    }

    @Test void projectReapprovalDoesNotHideManualRetryGuidanceOrResetItsRequest() throws Exception {
        jdbc.update("UPDATE review_request SET state='FAILED',result_code='RATE_LIMIT_EXHAUSTED',finished_at=CURRENT_TIMESTAMP WHERE project_id=?", projectId);
        for (String projectState : new String[] {"PAUSED", "APPROVED"}) {
            jdbc.update("UPDATE project SET status=? WHERE id=?", projectState, projectId);
            for (String username : new String[] {"queue-owner", "queue-admin"}) {
                for (String path : new String[] {"/projects/" + projectId, "/reviews?projectId=" + projectId}) {
                    var response = mvc.perform(get(path).with(user(accounts.loadUserByUsername(username))))
                            .andExpect(status().isOk()).andReturn();
                    var snapshot = (ReviewRequestView) response.getModelAndView().getModel().get("reviewRequest");
                    assertThat(snapshot.rateLimitExhausted()).isTrue();
                    assertThat(snapshot.active()).isFalse();
                    assertThat(snapshot.resultLabel()).contains("새 예약으로 자동 재접수하지 않습니다");
                    assertThat(snapshot.toString()).doesNotContain(requestId, "RATE_LIMIT_EXHAUSTED");
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT request_id FROM review_request WHERE project_id=?", String.class, projectId)).isEqualTo(requestId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }
}
