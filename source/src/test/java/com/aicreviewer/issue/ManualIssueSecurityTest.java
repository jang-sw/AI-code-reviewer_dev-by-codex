package com.aicreviewer.issue;

import com.aicreviewer.identity.AccountUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual_issue_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ManualIssueSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES(101,'manual-admin','unused','manual-admin','ADMIN'),(102,'manual-assignee','unused','manual-assignee','USER'),(103,'manual-other','unused','manual-other','USER')");
        jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES(110,'Manual project','https://github.com/org/manual-fixture','GITHUB','github.com','org/manual-fixture',103,'APPROVED')");
        jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,summary,coverage_type) VALUES(120,110,?,'Manual task','MANUAL_ONLY')", "a".repeat(40));
        jdbc.update("INSERT INTO manual_review_file(id,reviewed_commit_id,project_id,file_path,new_object_sha,new_mode,reason_code) VALUES(130,120,110,'image.bin',?,'100644','GIT_DIFF_BUDGET')", "b".repeat(40));
        jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,issue_kind,manual_file_id) VALUES(140,110,120,102,NULL,'Manual task','image.bin','Description','Inspect the file','MANUAL_REVIEW',130)");
    }

    @Test
    void manualEvidenceIsVisibleOnlyToAssigneeAndAdministrator() throws Exception {
        mvc.perform(get("/issues/140").with(user(accounts.loadUserByUsername("manual-assignee"))))
                .andExpect(status().isOk()).andExpect(model().attribute("pageTitle", "수동 확인 업무"));
        mvc.perform(get("/issues/140").with(user(accounts.loadUserByUsername("manual-admin"))))
                .andExpect(status().isOk());
        mvc.perform(get("/issues/140").with(user(accounts.loadUserByUsername("manual-other"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/issues/140")).andExpect(status().is3xxRedirection());
    }

    @Test
    void csrfAndAssigneePermissionAreRequiredBeforeManualCompletion() throws Exception {
        mvc.perform(request("RESOLVED", "파일을 직접 확인하고 필요한 조치를 완료했습니다")
                        .with(user(accounts.loadUserByUsername("manual-assignee"))))
                .andExpect(status().isForbidden());
        mvc.perform(request("RESOLVED", "파일을 직접 확인하고 필요한 조치를 완료했습니다")
                        .with(user(accounts.loadUserByUsername("manual-other"))).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(row()).containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test
    void manualCompletionRequiresReasonAndKeepsOriginalKindAndListSelection() throws Exception {
        mvc.perform(request("RESOLVED", "").with(user(accounts.loadUserByUsername("manual-assignee"))).with(csrf()))
                .andExpect(status().isBadRequest());
        String reason = "파일을 직접 확인했고 변경 내용에 문제가 없습니다";
        var result = mvc.perform(request("RESOLVED", reason).with(user(accounts.loadUserByUsername("manual-assignee"))).with(csrf()))
                .andExpect(redirectedUrl("/issues?status=&page=2")).andReturn();
        assertThat(result.getFlashMap().toString()).doesNotContain(reason);
        assertThat(row()).containsEntry("status", "RESOLVED").containsEntry("issue_kind", "MANUAL_REVIEW")
                .containsEntry("resolution_note", reason).containsEntry("severity", null);
        assertThat(jdbc.queryForObject("SELECT coverage_type FROM reviewed_commit WHERE id=120", String.class)).isEqualTo("MANUAL_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_review_file", Long.class)).isEqualTo(1);
        mvc.perform(request("OPEN", "").with(user(accounts.loadUserByUsername("manual-admin"))).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(request("OPEN", "새로운 변경 영향이 발견되어 다시 확인합니다").with(user(accounts.loadUserByUsername("manual-admin"))).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(row()).containsEntry("status", "OPEN");
    }

    @Test
    void authorizedInvalidReasonKeepsTheEditableDetailAndDoesNotChangeStateOrAudit() throws Exception {
        String reason = "완료   ";
        mvc.perform(request("DISMISSED", reason).with(user(accounts.loadUserByUsername("manual-assignee"))).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(view().name("issue-detail"))
                .andExpect(model().attribute("reasonError", ManualIssueReasonException.SAFE_MESSAGE))
                .andExpect(model().attribute("reasonFormValue", reason))
                .andExpect(model().attribute("requestedIssueStatus", "DISMISSED"))
                .andExpect(model().attribute("filterStatus", "")).andExpect(model().attribute("page", 2));
        assertThat(row()).containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test
    void invalidReasonCannotRevealAnUnrelatedOrMissingIssue() throws Exception {
        for (long id : new long[] {140, 999999}) {
            var result = mvc.perform(post("/issues/" + id + "/status").param("status", "RESOLVED").param("reason", "four")
                            .with(user(accounts.loadUserByUsername("manual-other"))).with(csrf()))
                    .andExpect(status().isNotFound()).andReturn();
            assertThat(result.getModelAndView()).isNull();
            assertThat(result.getFlashMap().isEmpty()).isTrue();
        }
        assertThat(row()).containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    private MockHttpServletRequestBuilder request(String status, String reason) {
        return post("/issues/140/status").param("status", status).param("reason", reason).param("filterStatus", "").param("page", "2");
    }
    private java.util.Map<String, Object> row() {
        return jdbc.queryForMap("SELECT status,issue_kind,severity,resolution_note FROM review_issue WHERE id=140");
    }
}
