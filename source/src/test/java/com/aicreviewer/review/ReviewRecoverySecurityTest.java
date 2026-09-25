package com.aicreviewer.review;

import com.aicreviewer.identity.AccountUserDetailsService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:review_recovery_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewRecoverySecurityTest {
    private static final String CURSOR = "a".repeat(40);
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;
    @MockitoBean ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;
    private String admin;
    private String owner;
    private String other;
    private String url;
    private long projectId;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        admin = "admin" + suffix;
        owner = "owner" + suffix;
        other = "other" + suffix;
        url = "https://github.com/org/recovery-" + suffix;
        for (String name : new String[] {admin, owner, other}) {
            jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES(?,?,?,?)", name, "unused-fixture-hash", name,
                    name.equals(admin) ? "ADMIN" : "USER");
        }
        long ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, owner);
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status,last_reviewed_sha) VALUES('Recovery',?,'GITHUB','github.com',?,?,'PAUSED',?)",
                url, "org/recovery-" + suffix, ownerId, CURSOR);
        projectId = jdbc.queryForObject("SELECT id FROM project WHERE repository_url=?", Long.class, url);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(anyLong())).thenReturn(Optional.of(lease));
    }

    @Test
    void csrfAndAdminAreRequiredBeforeTheServiceCanTakeALease() throws Exception {
        mvc.perform(request().with(user(accounts.loadUserByUsername(admin)))).andExpect(status().isForbidden());
        mvc.perform(request().with(user(accounts.loadUserByUsername(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(request().with(user(accounts.loadUserByUsername(other))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(request().with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(cursor()).isEqualTo(CURSOR);
        verifyNoInteractions(locks, lease);
    }

    @Test
    void administratorReceivesInternalRedirectAfterAtomicReset() throws Exception {
        var result = mvc.perform(request().with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(redirectedUrl("/projects/" + projectId)).andExpect(flash().attributeExists("notice")).andReturn();
        assertThat((Map<String, Object>) result.getFlashMap()).containsOnlyKeys("notice");
        assertThat(cursor()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id=?", String.class, projectId)).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='PROJECT_REVIEW_PROGRESS_RESET' AND target_id=?", Long.class, projectId))
                .isEqualTo(1);
        verify(lease).close();
    }

    @Test
    void busyReviewAndStaleConfirmationReturnConflictWithoutMutation() throws Exception {
        when(locks.tryAcquire(projectId)).thenReturn(Optional.empty());
        mvc.perform(request().with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(status().isConflict()).andExpect(view().name("review-recovery-error"))
                .andExpect(model().attribute("projectId", projectId))
                .andExpect(model().attribute("recoveryErrorMessage", new ReviewRecoveryException(ReviewRecoveryException.Failure.BUSY).safeMessage()));
        assertThat(cursor()).isEqualTo(CURSOR);
        when(locks.tryAcquire(projectId)).thenReturn(Optional.of(lease));
        jdbc.update("UPDATE project SET last_reviewed_sha=? WHERE id=?", "b".repeat(40), projectId);
        mvc.perform(request().with(user(accounts.loadUserByUsername(admin))).with(csrf()))
                .andExpect(status().isConflict()).andExpect(view().name("review-recovery-error"))
                .andExpect(model().attribute("recoveryErrorMessage", new ReviewRecoveryException(ReviewRecoveryException.Failure.STALE_CURSOR).safeMessage()));
        assertThat(cursor()).isEqualTo("b".repeat(40));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE target_id=? AND action='PROJECT_REVIEW_PROGRESS_RESET'", Long.class, projectId)).isZero();
    }

    @Test
    void missingFieldsAndNonPostRequestsCannotResetProgress() throws Exception {
        mvc.perform(post(path()).with(user(accounts.loadUserByUsername(admin))).with(csrf())
                        .param("repositoryUrl", url).param("expectedCursor", CURSOR))
                .andExpect(status().isBadRequest()).andExpect(view().name("review-recovery-error"));
        mvc.perform(get(path()).with(user(accounts.loadUserByUsername(admin)))).andExpect(status().isMethodNotAllowed());
        assertThat(cursor()).isEqualTo(CURSOR);
        verifyNoInteractions(locks, lease);
    }

    private MockHttpServletRequestBuilder request() {
        return post(path()).param("repositoryUrl", url).param("expectedCursor", CURSOR).param("reason", "강제 푸시 이력 변경 확인");
    }
    private String path() { return "/admin/projects/" + projectId + "/review-progress/reset"; }
    private String cursor() { return jdbc.queryForObject("SELECT last_reviewed_sha FROM project WHERE id=?", String.class, projectId); }
}
