package com.aicreviewer.operations;

import com.aicreviewer.identity.AccountUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        "spring.datasource.url=jdbc:h2:mem:operations_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.operations.stale-after-minutes=120"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OperationsSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountUserDetailsService accounts;
    @Autowired MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES('ops-admin','unused','ops-admin','ADMIN'),('ops-member','unused','ops-member','USER')");
        long ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='ops-member'", Long.class);
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES(?,'https://github.com/private/operations','GITHUB','github.com','private/operations',?,'APPROVED')",
                "Project <script>untrusted</script>", ownerId);
    }

    @Test
    void anonymousAndOrdinaryAccountsCannotReadTheOperationsPage() throws Exception {
        mvc.perform(get("/admin/operations")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-member"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-member")))
                        .param("filter", "invalid").param("page", "-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorGetsDefaultObservationAndValidatedFilterPage() throws Exception {
        var result = mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin"))))
                .andExpect(status().isOk()).andExpect(view().name("admin/operations"))
                .andExpect(model().attribute("pageTitle", "운영 현황")).andReturn();
        var page = (OperationsService.OperationsPage) result.getModelAndView().getModel().get("operations");
        assertThat(page.filter()).isEqualTo("ATTENTION");
        assertThat(page.projects()).hasSize(1);
        assertThat(page.projects().getFirst().neverRun()).isTrue();
        assertThat(page.toString()).doesNotContain("github.com", "private/operations", "password_hash");
        var filtered = mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin")))
                        .param("filter", "FAILED").param("page", "1"))
                .andExpect(status().isOk()).andReturn();
        var filteredPage = (OperationsService.OperationsPage) filtered.getModelAndView().getModel().get("operations");
        assertThat(filteredPage.filter()).isEqualTo("FAILED");
        assertThat(filteredPage.page()).isEqualTo(1);
        assertThat(filteredPage.projects()).isEmpty();
    }

    @Test
    void invalidQueryParametersAreRejectedRatherThanBroadeningTheQuery() throws Exception {
        for (String page : new String[] {"-1", "10001", "2147483647", "not-a-number"}) {
            mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin"))).param("page", page))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin"))).param("filter", "ALL"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin")))
                        .param("filter", "STALE").param("page", "10000"))
                .andExpect(status().isOk());
    }

    @Test
    void pageOffersNoMutationEndpointEvenForAnAdministratorWithCsrf() throws Exception {
        mvc.perform(post("/admin/operations").with(user(accounts.loadUserByUsername("ops-admin"))).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }
}
