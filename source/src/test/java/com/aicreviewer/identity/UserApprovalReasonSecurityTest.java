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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The real embedded-Tomcat PostgreSQL suite separately checks JSP HTML escaping. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_reason_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserApprovalReasonSecurityTest {
    private static final String PASSWORD = "Approval-fixture-7219!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String REASON = "<script>fixture</script> & 관리자 검토 내용";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService users;
    @Autowired AccountUserDetailsService details;
    private long applicant;

    @BeforeEach void setup() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) " +
                "VALUES ('administrator',?,'admin-git','ADMIN'),('member',?,'member-git','USER')", HASH, HASH);
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled,approval_status) " +
                "VALUES ('applicant',?,'applicant-git','USER',FALSE,'PENDING')", HASH);
        applicant = jdbc.queryForObject("SELECT id FROM app_user WHERE username='applicant'", Long.class);
    }

    @Test void administratorCanReadCurrentAndHistoricalReasonWhileMutationKeepsListFilters() throws Exception {
        mvc.perform(post("/admin/users/" + applicant + "/reject")
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("reason", "  " + REASON + "  ").param("status", "PENDING").param("search", " Applicant ").param("page", "3"))
                .andExpect(redirectedUrl("/admin/users?status=PENDING&search=Applicant&page=3"))
                .andExpect(flash().attributeExists("notice"))
                .andExpect(flash().attributeCount(1));

        var page = mvc.perform(get("/admin/users").with(user(details.loadUserByUsername("administrator")))
                        .param("status", "REJECTED").param("search", "applicant").param("page", "0"))
                .andExpect(status().isOk()).andExpect(view().name("admin/users"))
                .andExpect(model().attribute("approvalFilter", "REJECTED"))
                .andExpect(model().attribute("search", "applicant")).andExpect(model().attribute("page", 0))
                .andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked") var accounts = (List<UserAccount>) page.get("users");
        assertThat(accounts).singleElement().satisfies(account -> assertThat(account.approvalReason()).isEqualTo(REASON));
        assertThat(accounts.toString()).doesNotContain(PASSWORD, HASH);

        mvc.perform(post("/admin/users/" + applicant + "/reopen")
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("status", "REJECTED").param("search", "applicant").param("page", "1"))
                .andExpect(redirectedUrl("/admin/users?status=REJECTED&search=applicant&page=1"));
        mvc.perform(post("/admin/users/" + applicant + "/approve")
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(users.requireAccount("applicant").approvalReason()).isNull();

        var audit = mvc.perform(get("/admin/audit").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isOk()).andExpect(view().name("admin/audit"))
                .andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked") var events = (List<Map<String, Object>>) audit.get("events");
        assertThat(events).extracting(event -> event.get("detail"))
                .contains("PENDING → REJECTED; 반려 사유: " + REASON, "REJECTED → PENDING; 이전 반려 사유: " + REASON);
        assertThat(events.toString()).doesNotContain(PASSWORD, HASH);
    }

    @Test void currentAndHistoricalReasonsStayUnavailableToOrdinaryAndAnonymousReaders() throws Exception {
        users.decideApproval("administrator", applicant, "reject", REASON);
        for (String path : List.of("/admin/users", "/admin/audit")) {
            var member = mvc.perform(get(path).with(user(details.loadUserByUsername("member"))))
                    .andExpect(status().isForbidden()).andReturn();
            var anonymous = mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(member.getResponse().getContentAsString()).doesNotContain(REASON);
            assertThat(anonymous.getResponse().getContentAsString()).doesNotContain(REASON);
        }
        mvc.perform(post("/admin/users/" + applicant + "/reopen").with(user(details.loadUserByUsername("member"))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/" + applicant + "/reopen").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT approval_reason FROM app_user WHERE id=?", String.class, applicant)).isEqualTo(REASON);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='USER_APPROVAL_PENDING'", Integer.class)).isZero();
    }
}
