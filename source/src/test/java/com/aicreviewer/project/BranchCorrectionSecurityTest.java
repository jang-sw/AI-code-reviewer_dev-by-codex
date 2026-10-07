package com.aicreviewer.project;

import com.aicreviewer.identity.AccountUserDetailsService;
import com.aicreviewer.review.ProjectReviewLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:branch_correction_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BranchCorrectionSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;
    @MockitoBean ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;
    private String admin;
    private String owner;
    private long projectId;

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        admin = "admin" + suffix;
        owner = "owner" + suffix;
        for (String username : new String[] {admin, owner}) {
            jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES(?,'non-authenticating-fixture',?,?)",
                    username, username, username.equals(admin) ? "ADMIN" : "USER");
        }
        long ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, owner);
        String url = "https://github.com/org/correction-" + suffix;
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status,review_branch) " +
                "VALUES('Correction',?,'GITHUB','github.com',?,?,'PENDING','mian')", url, "org/correction-" + suffix, ownerId);
        projectId = jdbc.queryForObject("SELECT id FROM project WHERE repository_url=?", Long.class, url);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(anyLong())).thenReturn(Optional.of(lease));
    }

    @Test void onlyCurrentAdminWithCsrfCanReachCorrection() throws Exception {
        mvc.perform(request().with(user(accounts.loadUserByUsername(admin)))).andExpect(status().isForbidden());
        mvc.perform(request().with(user(accounts.loadUserByUsername(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(request().with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get(path()).with(user(accounts.loadUserByUsername(admin)))).andExpect(status().isMethodNotAllowed());
        assertThat(branch()).isEqualTo("mian");
        verifyNoInteractions(locks, lease);
    }

    @Test void successRedirectsInternallyWithoutAutoApprovalOrInputFlash() throws Exception {
        var result = mvc.perform(request().with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(redirectedUrl("/projects/" + projectId)).andExpect(flash().attributeExists("notice")).andReturn();
        assertThat((Map<String, Object>) result.getFlashMap()).containsOnlyKeys("notice");
        assertThat(branch()).isEqualTo("main");
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id=?", String.class, projectId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_request WHERE project_id=?", Long.class, projectId)).isZero();
        verify(lease).close();
    }

    @Test void validationRendersAnAuthorizedEditableFormWithoutSavingOrUsingFlash() throws Exception {
        var result = mvc.perform(request().with(value("reviewBranch", "bad branch"))
                        .with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(view().name("projects/detail"))
                .andExpect(model().attribute("isAdmin", true))
                .andExpect(model().attribute("branchCorrectionForm", Map.of("reviewBranch", "bad branch", "reason", "등록 브랜치의 오타를 확인했습니다")))
                .andExpect(model().attributeExists("branchCorrectionError")).andReturn();
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(branch()).isEqualTo("mian");
        assertThat(result.getModelAndView().getModel()).doesNotContainKey("confirmed");
        verifyNoInteractions(locks, lease);
    }

    @Test void staleValuesAreNotEchoedAndCurrentProjectIsShownForFreshConfirmation() throws Exception {
        var result = mvc.perform(request().with(value("expectedBranch", "private-stale-value"))
                        .with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(status().isConflict()).andExpect(view().name("projects/detail")).andReturn();
        var model = result.getModelAndView().getModel();
        assertThat(((Project) model.get("project")).reviewBranch()).isEqualTo("mian");
        assertThat(model.toString()).doesNotContain("private-stale-value");
        assertThat(model).doesNotContainKeys("confirmed", "expectedBranch", "expectedCursor");
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(branch()).isEqualTo("mian");
    }

    @Test void oversizeControlAndRecognizableCredentialInputAreNotRestored() throws Exception {
        for (String reason : new String[] {"x".repeat(501), "secret\nreason", "token=synthetic-fixture"}) {
            var result = mvc.perform(request().with(value("confirmed", "false")).with(value("reason", reason))
                            .with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                    .andExpect(status().isBadRequest()).andExpect(view().name("projects/detail"))
                    .andExpect(model().attribute("branchCorrectionCleared", true))
                    .andExpect(model().attribute("branchCorrectionForm", Map.of("reviewBranch", "main", "reason", ""))).andReturn();
            assertThat(result.getModelAndView().getModel().toString()).doesNotContain(reason);
            assertThat(result.getFlashMap().isEmpty()).isTrue();
        }
        verifyNoInteractions(locks, lease);
    }

    @Test void revokedAdministratorDuringLeaseAcquisitionDoesNotReceiveAnErrorFormWithInputs() throws Exception {
        when(locks.tryAcquire(projectId)).thenAnswer(invocation -> {
            jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username=?", admin);
            return Optional.of(lease);
        });
        var result = mvc.perform(request().with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getModelAndView()).isNull();
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(branch()).isEqualTo("mian");
        verify(lease).close();
    }

    private MockHttpServletRequestBuilder request() {
        return post(path()).param("expectedBranch", "mian").param("expectedCursor", "").param("reviewBranch", "main")
                .param("reason", "등록 브랜치의 오타를 확인했습니다").param("confirmed", "true");
    }
    private String path() { return "/admin/projects/" + projectId + "/branch"; }
    private String branch() { return jdbc.queryForObject("SELECT review_branch FROM project WHERE id=?", String.class, projectId); }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor value(String key, String value) {
        return request -> { request.setParameter(key, value); return request; };
    }
}
