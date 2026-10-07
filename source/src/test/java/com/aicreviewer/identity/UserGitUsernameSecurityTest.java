package com.aicreviewer.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:git_username_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserGitUsernameSecurityTest {
    private static final String HASH = new BCryptPasswordEncoder(4).encode("Synthetic-password-7219!");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService details;
    private long target;

    @BeforeEach void setup() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES " +
                "('administrator',?,'admin-git','ADMIN'),('member',?,'member-git','USER')", HASH, HASH);
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled,approval_status) " +
                "VALUES ('applicant',?,'incorrect-git','USER',FALSE,'REJECTED')", HASH);
        target = jdbc.queryForObject("SELECT id FROM app_user WHERE username='applicant'", Long.class);
    }

    @Test void administratorCorrectsRejectedAccountWithoutApprovalAndPreservesListContext() throws Exception {
        var before = jdbc.queryForMap("SELECT username,password_hash,role,enabled,approval_status,security_version FROM app_user WHERE id=?", target);
        mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "  Corrected.Git  ")
                        .param("status", "REJECTED").param("search", " applicant ").param("page", "2")
                        .param("role", "ADMIN").param("enabled", "true").param("approvalStatus", "APPROVED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users?status=REJECTED&search=applicant&page=2"))
                .andExpect(flash().attributeExists("notice")).andExpect(flash().attributeCount(1));
        assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=?", String.class, target)).isEqualTo("corrected.git");
        assertThat(jdbc.queryForMap("SELECT username,password_hash,role,enabled,approval_status,security_version FROM app_user WHERE id=?", target)).isEqualTo(before);
    }

    @Test void stalePageReturns409WithCurrentTargetAndSafeAttemptEvenOutsideTheCurrentListPage() throws Exception {
        jdbc.update("UPDATE app_user SET git_username='already-corrected' WHERE id=?", target);
        var result = mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "intended-name")
                        .param("status", "PENDING").param("search", "different-account").param("page", "3"))
                .andExpect(status().isConflict()).andExpect(view().name("admin/users"))
                .andExpect(model().attribute("gitCorrectionValue", "intended-name"))
                .andExpect(model().attribute("approvalFilter", "PENDING")).andExpect(model().attribute("search", "different-account"))
                .andExpect(model().attribute("page", 3)).andExpect(model().attributeExists("gitCorrectionError"))
                .andExpect(flash().attributeCount(0)).andReturn();
        UserAccount account = (UserAccount) result.getModelAndView().getModel().get("gitCorrectionTarget");
        assertThat(account.id()).isEqualTo(target);
        assertThat(account.gitUsername()).isEqualTo("already-corrected");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void duplicateNameHasFriendlyAdminOnlyConflictAndValidationErrorNeverReflectsUnsafeInput() throws Exception {
        mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "member-git"))
                .andExpect(status().isConflict()).andExpect(view().name("admin/users"))
                .andExpect(model().attribute("gitCorrectionValue", "member-git"));
        for (String input : new String[] {"<script>private</script>", "private\u0000token", "x".repeat(101),
                "sk-" + "proj-" + "a".repeat(28), "password=fixture-private-value"}) {
            var result = mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator"))).with(csrf())
                            .param("expectedGitUsername", "incorrect-git").param("newGitUsername", input))
                    .andExpect(status().isBadRequest()).andExpect(view().name("admin/users"))
                    .andExpect(model().attribute("gitCorrectionValue", ""))
                    .andExpect(flash().attributeCount(0)).andReturn();
            assertThat(result.getModelAndView().getModel().toString()).doesNotContain(input);
        }
        assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=?", String.class, target)).isEqualTo("incorrect-git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void unauthorizedMissingCsrfAndRevokedAdminCannotCorrectOrLearnDuplicateDetails() throws Exception {
        mvc.perform(post(path()).with(csrf()).param("expectedGitUsername", "incorrect-git").param("newGitUsername", "member-git"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post(path()).with(user(details.loadUserByUsername("member"))).with(csrf())
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "member-git"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator")))
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "fresh"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET role='USER' WHERE username='administrator'");
        mvc.perform(post(path()).with(user("administrator").roles("ADMIN")).with(csrf())
                        .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "member-git"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=?", String.class, target)).isEqualTo("incorrect-git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void malformedReturnContextIsRejectedBeforeMutationAndUnknownTargetRemains404() throws Exception {
        for (String[] context : new String[][] {{"page", "-1"}, {"page", "10001"}, {"status", "SECRET"}, {"search", "x".repeat(81)}}) {
            mvc.perform(post(path()).with(user(details.loadUserByUsername("administrator"))).with(csrf())
                            .param("expectedGitUsername", "incorrect-git").param("newGitUsername", "fresh").param(context[0], context[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/admin/users/999999/git-username").with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("expectedGitUsername", "missing").param("newGitUsername", "fresh"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=?", String.class, target)).isEqualTo("incorrect-git");
    }

    private String path() { return "/admin/users/" + target + "/git-username"; }
}
