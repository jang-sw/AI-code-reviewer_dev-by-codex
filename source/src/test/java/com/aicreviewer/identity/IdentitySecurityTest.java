package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdentitySecurityTest {
    private static final String PASSWORD = "Test-password-5189!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService users;
    @Autowired AccountUserDetailsService details;
    @Autowired PasswordEncoder passwords;
    @Autowired LoginAttemptLimiter limiter;
    @Autowired MockMvc mvc;
    private long adminId;
    private long aliceId;

    @BeforeEach
    void accounts() {
        adminId = add("administrator", "admin-git", "ADMIN");
        aliceId = add("alice", "alice-git", "USER");
    }

    @Test
    void anonymousCannotAccessApplicationAndLoginUsesCaseInsensitiveUsername() throws Exception {
        mvc.perform(get("/projects")).andExpect(status().is3xxRedirection());
        mvc.perform(formLogin().user(" ALICE ").password(PASSWORD)).andExpect(redirectedUrl("/"));
        mvc.perform(formLogin().user("alice").password("wrong-password")).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void csrfAndAdminRoleAreRequiredForUserCreation() throws Exception {
        mvc.perform(post("/admin/users").with(user(details.loadUserByUsername("administrator")))
                        .param("username", "created").param("password", PASSWORD).param("gitUsername", "created-git"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users").with(user(details.loadUserByUsername("alice"))).with(csrf())
                        .param("username", "created").param("password", PASSWORD).param("gitUsername", "created-git"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = 'created'", Integer.class)).isZero();
        assertThatThrownBy(() -> users.create("alice", "created", PASSWORD, "created-git", "ADMIN"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createdAccountIsNormalizedHashedAndAuditedWithoutSecrets() {
        long id = users.create("administrator", "New.User", PASSWORD, "Git-User", "USER");
        UserAccount account = users.requireAccount("new.user");
        assertThat(account.id()).isEqualTo(id);
        assertThat(account.gitUsername()).isEqualTo("git-user");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, id);
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_event WHERE target_id = ?", String.class, id)).doesNotContain(PASSWORD, hash);
        assertThatThrownBy(() -> users.create("administrator", "new.user", PASSWORD, "other-git", "USER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordsRespectUnicodeLengthAndBcryptByteLimit() {
        assertThatThrownBy(() -> AccountInput.password("a".repeat(11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountInput.password("한".repeat(25))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AccountInput.password("한".repeat(24))).hasSize(24);
        assertThatThrownBy(() -> AccountInput.password("😀".repeat(11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.create("administrator", "valid", PASSWORD, "valid", "SUPERUSER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastActiveAdministratorCannotBeDisabled() {
        assertThatThrownBy(() -> users.setEnabled("administrator", adminId, false)).isInstanceOf(IllegalArgumentException.class);
        long anotherAdmin = add("secondadmin", "second-git", "ADMIN");
        users.setEnabled("administrator", anotherAdmin, false);
        assertThatThrownBy(() -> users.setEnabled("administrator", adminId, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(users.requireAccount("administrator").enabled()).isTrue();
    }

    @Test
    void disablingAccountRevokesAnExistingSessionAndPreventsNewLogin() throws Exception {
        MockHttpSession session = login("alice");
        users.setEnabled("administrator", aliceId, false);
        mvc.perform(get("/projects").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(formLogin().user("alice").password(PASSWORD)).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void reenablingAnAccountDoesNotRestoreSessionsFromBeforeDisabling() throws Exception {
        MockHttpSession session = login("alice");
        users.setEnabled("administrator", aliceId, false);
        users.setEnabled("administrator", aliceId, true);
        mvc.perform(get("/projects").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(formLogin().user("alice").password(PASSWORD)).andExpect(redirectedUrl("/"));
    }

    @Test
    void administratorPasswordResetRevokesExistingSessions() throws Exception {
        MockHttpSession session = login("alice");
        users.resetPassword("administrator", aliceId, "New-password-8392!");
        mvc.perform(get("/projects").session(session)).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(formLogin().user("alice").password(PASSWORD)).andExpect(redirectedUrl("/login?error"));
        mvc.perform(formLogin().user("alice").password("New-password-8392!")).andExpect(redirectedUrl("/"));
    }

    @Test
    void revokedSessionWithValidCsrfCannotSubmitAndKeepsTheExistingLogoutFlow() throws Exception {
        MockHttpSession session = login("alice");
        users.setEnabled("administrator", aliceId, false);
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId);
        long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
        mvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("newPassword", "Replacement-password-5321!")
                        .param("confirmPassword", "Replacement-password-5321!"))
                .andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(auditCount);
    }

    @Test
    void invalidCsrfPreservesTheAccountAndDoesNotReachPasswordMutation() throws Exception {
        MockHttpSession session = login("alice");
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId);
        mvc.perform(post("/account/password").session(session).with(csrf().useInvalidToken())
                        .param("currentPassword", PASSWORD).param("newPassword", "Replacement-password-5321!")
                        .param("confirmPassword", "Replacement-password-5321!"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertThat(result.getRequest().getAttribute(
                        com.aicreviewer.web.SafeAccessDeniedHandler.CSRF_FAILURE)).isEqualTo(Boolean.TRUE));
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void passwordResetKeepsADisabledApprovedAccountDisabledAndPreservesOtherFields() {
        users.setEnabled("administrator", aliceId, false);
        var before = new java.util.LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId));
        users.resetPassword("administrator", aliceId, "Replacement-password-5901!");
        var after = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId);
        assertThat(passwords.matches("Replacement-password-5901!", (String) after.get("password_hash"))).isTrue();
        before.put("password_hash", after.get("password_hash"));
        before.put("security_version", ((Number) before.get("security_version")).longValue() + 1);
        assertThat(after).isEqualTo(before).containsEntry("enabled", false).containsEntry("approval_status", "APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='USER_PASSWORD_RESET' AND target_id=?",
                Long.class, aliceId)).isEqualTo(1);
    }

    @Test
    void invalidPasswordResetAndChangeDoNotModifyTheLockedAccountOrCreateAudit() {
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId);
        assertThatThrownBy(() -> users.resetPassword("administrator", aliceId, "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.changePassword("alice", PASSWORD, "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.changePassword("alice", PASSWORD, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", aliceId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test
    void selfPasswordChangeRequiresCurrentPasswordAndMatchingConfirmation() throws Exception {
        MockHttpSession session = login("alice");
        mvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", "wrong").param("newPassword", "New-password-8392!")
                        .param("confirmPassword", "New-password-8392!"))
                .andExpect(redirectedUrl("/account/password"));
        assertThat(passwords.matches(PASSWORD, details.loadUserByUsername("alice").getPassword())).isTrue();
        mvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", PASSWORD).param("newPassword", "New-password-8392!")
                        .param("confirmPassword", "New-password-8392!"))
                .andExpect(redirectedUrl("/login?changed"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void existingDatabaseNeverResetsTheAdministratorFromEnvironment() {
        users.bootstrap("administrator", "Changed-env-password!", "admin-git");
        assertThat(passwords.matches(PASSWORD, details.loadUserByUsername("administrator").getPassword())).isTrue();
    }

    @Test
    void emptyDatabaseRequiresExplicitBootstrapCredentials() {
        jdbc.update("DELETE FROM app_user");
        assertThatThrownBy(() -> users.bootstrap("", "", "")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
        users.bootstrap("firstadmin", PASSWORD, "first-git");
        assertThat(users.requireAccount("firstadmin").isAdmin()).isTrue();
        assertThat(passwords.matches(PASSWORD, details.loadUserByUsername("firstadmin").getPassword())).isTrue();
    }

    @Test
    void repeatedFailedLoginsAreThrottledWithRetryAfter() throws Exception {
        limiter.succeeded("alice");
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(formLogin().user("alice").password("incorrect-password"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(formLogin().user("alice").password(PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        limiter.succeeded("alice");
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin().user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/")).andReturn().getRequest().getSession(false);
    }

    private long add(String username, String git, String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled) VALUES(?,?,?,?,TRUE)", username, HASH, git, role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }
}
