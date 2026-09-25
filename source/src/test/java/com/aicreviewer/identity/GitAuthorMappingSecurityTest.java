package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:git_author_mapping_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.git.allowed-hosts=github.com,gitlab.example.com"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GitAuthorMappingSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired GitAuthorMappingService mappings;
    @Autowired AccountUserDetailsService details;
    @Autowired MockMvc mvc;
    private long aliceId;

    @BeforeEach
    void accounts() {
        add("administrator", "ADMIN", true);
        aliceId = add("alice", "USER", true);
        add("disableduser", "USER", false);
    }

    @Test
    void administratorCanCreateDeleteAndAuditWithoutEmailOrOriginInAuditText() throws Exception {
        mvc.perform(post("/admin/git-authors").with(user(details.loadUserByUsername("administrator"))).with(csrf())
                        .param("userId", Long.toString(aliceId)).param("repositoryOrigin", "https://GitLab.Example.com:443/")
                        .param("authorEmail", " Engineer+Build@Example.COM "))
                .andExpect(redirectedUrl("/admin/git-authors"));
        var mapping = mappings.list("administrator", 0).getFirst();
        assertThat(mapping.repositoryOrigin()).isEqualTo("https://gitlab.example.com");
        assertThat(mapping.authorEmail()).isEqualTo("engineer+build@example.com");
        assertThat(mapping.userId()).isEqualTo(aliceId);
        mvc.perform(post("/admin/git-authors/" + mapping.id() + "/delete")
                        .with(user(details.loadUserByUsername("administrator"))).with(csrf()))
                .andExpect(redirectedUrl("/admin/git-authors"));
        assertThat(mappings.list("administrator", 0)).isEmpty();
        var events = jdbc.queryForList("SELECT detail FROM audit_event WHERE target_type = 'GIT_AUTHOR_MAPPING' AND target_id = ? ORDER BY id", String.class, mapping.id());
        assertThat(events).hasSize(2).allSatisfy(detail -> assertThat(detail).isEqualTo("user_id=" + aliceId));
        assertThat(events.toString()).doesNotContain(mapping.authorEmail(), mapping.repositoryOrigin());
    }

    @Test
    void mappingEmailsAndMutationsAreRestrictedToAdministrators() throws Exception {
        long id = mappings.create("administrator", aliceId, "https://github.com", "dev@example.com");
        mvc.perform(get("/admin/git-authors")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/git-authors").with(user(details.loadUserByUsername("alice")))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/git-authors").with(user(details.loadUserByUsername("alice"))).with(csrf())
                        .param("userId", Long.toString(aliceId)).param("repositoryOrigin", "https://github.com")
                        .param("authorEmail", "different@example.com"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/git-authors/" + id + "/delete").with(user(details.loadUserByUsername("alice"))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> mappings.list("alice", 0)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> mappings.create("alice", aliceId, "https://github.com", "other@example.com"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> mappings.delete("alice", id)).isInstanceOf(ResponseStatusException.class);
        assertThat(mappings.list("administrator", 0)).hasSize(1);
    }

    @Test
    void createAndDeleteRequireCsrf() throws Exception {
        mvc.perform(post("/admin/git-authors").with(user(details.loadUserByUsername("administrator")))
                        .param("userId", Long.toString(aliceId)).param("repositoryOrigin", "https://github.com")
                        .param("authorEmail", "dev@example.com"))
                .andExpect(status().isForbidden());
        assertThat(mappings.list("administrator", 0)).isEmpty();
        long id = mappings.create("administrator", aliceId, "https://github.com", "dev@example.com");
        mvc.perform(post("/admin/git-authors/" + id + "/delete").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isForbidden());
        assertThat(mappings.list("administrator", 0)).hasSize(1);
    }

    @Test
    void equivalentOriginsAndEmailCaseCannotCreateAmbiguousMappings() {
        mappings.create("administrator", aliceId, "https://gitlab.example.com", "engineer@example.com");
        assertThatThrownBy(() -> mappings.create("administrator", aliceId, "HTTPS://GITLAB.EXAMPLE.COM:443/", " ENGINEER@EXAMPLE.COM "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미");
    }

    @Test
    void exactOriginsAndWholeEmailAddressesRemainIndependent() {
        mappings.create("administrator", aliceId, "https://gitlab.example.com", "engineer@example.com");
        mappings.create("administrator", aliceId, "http://gitlab.example.com", "engineer@example.com");
        mappings.create("administrator", aliceId, "https://gitlab.example.com:8443", "engineer@example.com");
        mappings.create("administrator", aliceId, "https://gitlab.example.com", "engineer+tag@example.com");
        mappings.create("administrator", aliceId, "https://gitlab.example.com", "engineer@other.example.com");
        assertThat(mappings.list("administrator", 0)).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://untrusted.example.com", "https://gitlab.example.com/group/repo",
            "https://username:secret@gitlab.example.com", "https://gitlab.example.com?query=yes",
            "https://gitlab.example.com#fragment", "https://gitlab.example.com:0", "http://github.com", "file:///gitlab.example.com" })
    void unsafeOriginsNeverCreateMappings(String origin) {
        assertThatThrownBy(() -> mappings.create("administrator", aliceId, origin, "engineer@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mappings.list("administrator", 0)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "only-local-name", "dev@", "@example.com", "User <dev@example.com>",
            "dev\n@example.com", "dev\0@example.com", "dev @example.com", "dev@@example.com" })
    void invalidEmailAddressesNeverCreateMappings(String email) {
        assertThatThrownBy(() -> mappings.create("administrator", aliceId, "https://gitlab.example.com", email))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mappings.list("administrator", 0)).isEmpty();
    }

    @Test
    void oversizedEmailAndUnknownOrDisabledTargetsAreRejected() {
        assertThatThrownBy(() -> mappings.create("administrator", aliceId, "https://gitlab.example.com", "a".repeat(310) + "@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mappings.create("administrator", Long.MAX_VALUE, "https://gitlab.example.com", "engineer@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        long disabled = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'disableduser'", Long.class);
        assertThatThrownBy(() -> mappings.create("administrator", disabled, "https://gitlab.example.com", "engineer@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mappings.list("administrator", 0)).isEmpty();
    }

    @Test
    void disabledMappingsRemainVisibleAndUserSearchTreatsWildcardsLiterally() {
        mappings.create("administrator", aliceId, "https://gitlab.example.com", "engineer@example.com");
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", aliceId);
        assertThat(mappings.list("administrator", 0).getFirst().userEnabled()).isFalse();
        add("dev_one", "USER", true);
        add("devtwo", "USER", true);
        assertThat(mappings.userOptions("administrator", "_")).extracting(GitAuthorMappingService.UserOption::username)
                .containsExactly("dev_one");
        assertThat(mappings.userOptions("administrator", "%")).isEmpty();
        assertThat(mappings.userOptions("administrator", "alice")).isEmpty();
    }

    @Test
    void adminScreenPaginatesMappingsAndBoundsUserOptions() throws Exception {
        for (int i = 0; i < 51; i++) {
            jdbc.update("INSERT INTO git_author_mapping(user_id,repository_origin,author_email) VALUES(?,?,?)",
                    aliceId, "https://github.com", "user" + i + "@example.com");
            add("extra" + i, "USER", true);
        }
        var model = mvc.perform(get("/admin/git-authors").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) model.get("mappings")).hasSize(50);
        assertThat((List<?>) model.get("userOptions")).hasSize(50);
        assertThat(model.get("hasNext")).isEqualTo(true);
        assertThat(model.get("hasMoreUsers")).isEqualTo(true);
        assertThat(mappings.list("administrator", 1)).hasSize(1);
        assertThatThrownBy(() -> mappings.list("administrator", -1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> mappings.delete("administrator", Long.MAX_VALUE)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private long add(String username, String role, boolean enabled) {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled) VALUES(?,?,?,?,?)",
                username, "unused-hash", username, role, enabled);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }
}
