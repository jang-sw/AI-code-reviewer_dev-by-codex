package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:signup_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.security.signup-address-attempts=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SignupSecurityTest {
    private static final String PASSWORD = "Signup-password-7219!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService users;
    @Autowired AccountUserDetailsService details;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void accounts() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES ('administrator',?,'admin-git','ADMIN'),('member',?,'member-git','USER')", HASH, HASH);
    }

    @Test
    void anonymousSignupRequiresCsrfAndCannotInjectRoleOrApproval() throws Exception {
        mvc.perform(get("/signup")).andExpect(status().isOk()).andExpect(view().name("signup"));
        mvc.perform(post("/signup").param("username", "applicant").param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD).param("gitUsername", "applicant-git"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/signup").with(csrf()).param("username", " Applicant ").param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD).param("gitUsername", " Applicant-Git ")
                        .param("role", "ADMIN").param("enabled", "true").param("approvalStatus", "APPROVED"))
                .andExpect(redirectedUrl("/signup?submitted"));
        var row = jdbc.queryForMap("SELECT * FROM app_user WHERE username='applicant'");
        assertThat(row).containsEntry("role", "USER").containsEntry("enabled", false).containsEntry("approval_status", "PENDING")
                .containsEntry("git_username", "applicant-git");
        String hash = (String) row.get("password_hash");
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(jdbc.queryForList("SELECT detail FROM audit_event", String.class)).allSatisfy(value -> assertThat(value).doesNotContain(PASSWORD, hash, "applicant-git"));
    }

    @Test
    void duplicateUsernameOrGitAccountGetsSameReceiptWithoutChangingExistingAccount() throws Exception {
        users.signup("applicant", PASSWORD, "applicant-git");
        for (String[] values : new String[][] { {"applicant", "new-git"}, {"another", "applicant-git"}, {"member", "another-git"} }) {
            mvc.perform(post("/signup").with(csrf()).param("username", values[0]).param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD).param("gitUsername", values[1]))
                    .andExpect(redirectedUrl("/signup?submitted"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT approval_status FROM app_user WHERE username='member'", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_SIGNUP_REQUESTED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void validationErrorsNeverEchoPasswordsOrCreateAccounts() throws Exception {
        var result = mvc.perform(post("/signup").with(csrf()).param("username", "applicant").param("password", PASSWORD)
                        .param("confirmPassword", "different").param("gitUsername", "applicant-git"))
                .andExpect(redirectedUrl("/signup")).andExpect(flash().attribute("error", "비밀번호 확인이 일치하지 않습니다."))
                .andExpect(flash().attribute("signupForm", Map.of("username", "applicant", "gitUsername", "applicant-git")))
                .andReturn();
        assertThat((java.util.Map<String, Object>) result.getFlashMap()).containsOnlyKeys("error", "signupForm");
        assertThat(result.getFlashMap().toString()).doesNotContain(PASSWORD, "different", "confirmPassword");
        var session = result.getRequest().getSession(false);
        if (session != null) {
            Collections.list(session.getAttributeNames()).forEach(name ->
                    assertThat(String.valueOf(session.getAttribute(name))).doesNotContain(PASSWORD, "different"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username='applicant'", Integer.class)).isZero();
        assertThatThrownBy(() -> users.signup("applicant", "short", "applicant-git")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.signup("applicant", PASSWORD, "https://github.com/user")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signupRetainedFieldsAreBoundedAndExcludeControlCharacters() throws Exception {
        mvc.perform(post("/signup").with(csrf()).param("username", "u".repeat(90) + "\n")
                        .param("password", PASSWORD).param("confirmPassword", "different")
                        .param("gitUsername", "g".repeat(110) + "\r"))
                .andExpect(redirectedUrl("/signup"))
                .andExpect(flash().attribute("signupForm", Map.of("username", "u".repeat(80), "gitUsername", "g".repeat(100))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "reopen", "enabled", "password"})
    void accountActionsReturnToValidatedFiltersWithEncodedSearch(String action) throws Exception {
        long id = targetForAction(action);
        var result = mvc.perform(post("/admin/users/" + id + "/" + action)
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("status", "").param("search", " Member & +#% ").param("page", "3")
                        .param("enabled", "false").param("newPassword", PASSWORD))
                .andExpect(redirectedUrl("/admin/users?status=&search=Member+%26+%2B%23%25&page=3"))
                .andExpect(flash().attributeExists("notice")).andReturn();
        assertThat(result.getFlashMap().toString()).doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT security_version FROM app_user WHERE id=?", Long.class, id))
                .isEqualTo("approve".equals(action) || "reject".equals(action) ? 1L : 2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "reopen", "enabled", "password"})
    void invalidReturnFiltersPreventEveryAccountMutation(String action) throws Exception {
        long id = targetForAction(action);
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", id);
        int auditCount = jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
        String[][] invalidFilters = {
                {"https://example.invalid", "", "0"}, {"APPROVED", "", "-1"},
                {"APPROVED", "", "10001"}, {"PENDING", "x".repeat(81), "0"},
                {"PENDING", "member\nname", "0"}, {"PENDING", "", "not-a-page"}
        };
        for (String[] filters : invalidFilters) {
            mvc.perform(post("/admin/users/" + id + "/" + action)
                            .with(user(details.loadUserByUsername("administrator"))).with(csrf())
                            .param("status", filters[0]).param("search", filters[1]).param("page", filters[2])
                            .param("enabled", "false").param("newPassword", PASSWORD))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(auditCount);
    }

    @Test
    void accountErrorsAlsoKeepFiltersAndNeverAcceptAnExternalReturnUrl() throws Exception {
        long adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='administrator'", Long.class);
        mvc.perform(post("/admin/users/" + adminId + "/enabled")
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("enabled", "false").param("status", "APPROVED")
                        .param("search", "administrator").param("page", "10000")
                        .param("returnUrl", "https://example.invalid/collect"))
                .andExpect(redirectedUrl("/admin/users?status=APPROVED&search=administrator&page=10000"))
                .andExpect(flash().attributeExists("error"));
        assertThat(users.requireAccount("administrator").enabled()).isTrue();
    }

    @Test
    void pendingCannotLoginOrUseAccountServicesUntilAdminApproval() throws Exception {
        long id = applicant();
        mvc.perform(formLogin().user("applicant").password(PASSWORD)).andExpect(redirectedUrl("/login?error"));
        assertThatThrownBy(() -> users.requireAccount("applicant")).isInstanceOf(ResponseStatusException.class);
        assertThat(details.loadUserByUsername("applicant").isEnabled()).isFalse();
        users.decideApproval("administrator", id, "approve", "");
        mvc.perform(formLogin().user("applicant").password(PASSWORD)).andExpect(redirectedUrl("/"));
        assertThat(users.requireAccount("applicant").approvalStatus()).isEqualTo("APPROVED");
        assertThat(users.requireAccount("applicant").role()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT approval_decided_at FROM app_user WHERE id=?", java.sql.Timestamp.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT security_version FROM app_user WHERE id=?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void onlyAdminWithCsrfCanApproveAndDirectAdminCreationHttpIsRemoved() throws Exception {
        long id = applicant();
        mvc.perform(post("/admin/users/" + id + "/approve").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/" + id + "/approve").with(user(details.loadUserByUsername("member"))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> users.decideApproval("member", id, "approve", "")).isInstanceOf(ResponseStatusException.class);
        mvc.perform(post("/admin/users").with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("username", "injected").param("password", PASSWORD).param("gitUsername", "injected-git"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/admin/users/" + id + "/approve").with(user(details.loadUserByUsername("administrator"))).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(users.requireAccount("applicant").enabled()).isTrue();
    }

    @Test
    void rejectThenReopenRequiresNewApprovalAndCannotBypassWithEnableOrReset() throws Exception {
        long id = applicant();
        assertThatThrownBy(() -> users.setEnabled("administrator", id, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.resetPassword("administrator", id, PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        users.decideApproval("administrator", id, "reject", "등록 정보를 확인해 주세요");
        assertThat(jdbc.queryForObject("SELECT approval_reason FROM app_user WHERE id=?", String.class, id)).isEqualTo("등록 정보를 확인해 주세요");
        mvc.perform(formLogin().user("applicant").password(PASSWORD)).andExpect(redirectedUrl("/login?error"));
        assertThatThrownBy(() -> users.decideApproval("administrator", id, "approve", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.setEnabled("administrator", id, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.resetPassword("administrator", id, PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        users.decideApproval("administrator", id, "reopen", "");
        assertThat(details.loadUserByUsername("applicant").isEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT approval_status FROM app_user WHERE id=?", String.class, id)).isEqualTo("PENDING");
        users.decideApproval("administrator", id, "approve", "");
        assertThat(users.requireAccount("applicant").enabled()).isTrue();
        assertThatThrownBy(() -> users.decideApproval("administrator", id, "reject", "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE target_id=? ORDER BY id", String.class, id))
                .containsExactly("USER_SIGNUP_REQUESTED", "USER_APPROVAL_REJECTED", "USER_APPROVAL_PENDING", "USER_APPROVAL_APPROVED");
    }

    @Test
    void databaseConstraintAlsoPreventsActivationBeforeApproval() {
        long id = applicant();
        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET enabled=TRUE WHERE id=?", id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvalInboxIsBoundedFilteredAndSearchEscapesWildcards() throws Exception {
        for (int index = 0; index < 52; index++) {
            jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled,approval_status) VALUES (?,?,?,'USER',FALSE,'PENDING')",
                    String.format("pending%02d", index), HASH, "pending-git-" + index);
        }
        assertThat(users.list("administrator", 0, "PENDING", "pending")).hasSize(51);
        assertThat(users.list("administrator", 1, "PENDING", "pending")).hasSize(2);
        assertThat(users.list("administrator", 0, "APPROVED", "")).hasSize(2);
        assertThat(users.list("administrator", 0, "", "%")).isEmpty();
        assertThat(users.list("administrator", 0, "", "_")).isEmpty();
        mvc.perform(get("/admin/users").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isOk()).andExpect(model().attribute("approvalFilter", "PENDING"))
                .andExpect(model().attribute("hasNext", true));
        mvc.perform(get("/admin/users").param("status", "").param("search", "administrator")
                        .with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isOk()).andExpect(model().attribute("approvalFilter", ""))
                .andExpect(model().attribute("hasNext", false));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10001, Integer.MAX_VALUE})
    void invalidApprovalInboxPagesAreRejected(int page) {
        assertThatThrownBy(() -> users.list("administrator", page, "", "")).isInstanceOf(ResponseStatusException.class);
    }

    private long applicant() {
        users.signup("applicant", PASSWORD, "applicant-git");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username='applicant'", Long.class);
    }

    private long targetForAction(String action) {
        long id = applicant();
        if ("reopen".equals(action)) users.decideApproval("administrator", id, "reject", "");
        if ("enabled".equals(action) || "password".equals(action)) users.decideApproval("administrator", id, "approve", "");
        return id;
    }
}
