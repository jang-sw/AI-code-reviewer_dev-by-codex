package com.aicreviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicreviewer.identity.AccountUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        "spring.datasource.url=jdbc:h2:mem:project_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.git.allowed-hosts=github.com,gitlab.example.com"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectSecurityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired AccountUserDetailsService details;
    @Autowired MockMvc mvc;

    @BeforeEach
    void accounts() {
        for (String username : new String[] { "administrator", "alice", "bob" }) {
            jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled) VALUES(?,?,?,?,TRUE)",
                    username, "unused-hash", username, username.equals("administrator") ? "ADMIN" : "USER");
        }
    }

    @Test
    void repositoryLinkCreatesPendingProjectWithNoInitialCursor() {
        long id = projects.request("alice", "", "https://github.com/Team/Repository.git", "");
        Project project = projects.getVisible("alice", id);
        assertThat(project.name()).isEqualTo("repository");
        assertThat(project.repositoryUrl()).isEqualTo("https://github.com/team/repository");
        assertThat(project.status()).isEqualTo("PENDING");
        assertThat(project.lastReviewedSha()).isNull();
        assertThat(project.approvedAt()).isNull();
        assertThatThrownBy(() -> projects.request("bob", "", "https://github.com/team/repository", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyOwnerAndAdministratorCanReadAProject() throws Exception {
        long id = projects.request("alice", "Private Project", "https://github.com/team/private.git", "main");
        assertThat(projects.list("bob", 0)).isEmpty();
        assertThat(projects.list("administrator", 0)).hasSize(1);
        mvc.perform(get("/projects/" + id).with(user(details.loadUserByUsername("bob")))).andExpect(status().isNotFound());
        mvc.perform(get("/projects/" + id).with(user(details.loadUserByUsername("alice")))).andExpect(status().isOk());
        assertThatThrownBy(() -> projects.getVisible("bob", id)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void approvalRequiresAdministratorAndCsrfAndKeepsCursorOnResume() throws Exception {
        long id = projects.request("alice", "", "https://github.com/team/repository", "");
        mvc.perform(post("/admin/projects/" + id + "/approve").with(user(details.loadUserByUsername("alice"))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/projects/" + id + "/approve").with(user(details.loadUserByUsername("administrator"))))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> projects.transition("alice", id, "approve")).isInstanceOf(ResponseStatusException.class);
        projects.transition("administrator", id, "approve");
        assertThat(projects.getVisible("alice", id).approvedAt()).isNotNull();
        jdbc.update("UPDATE project SET last_reviewed_sha = ? WHERE id = ?", "a".repeat(40), id);
        projects.transition("administrator", id, "pause");
        projects.transition("administrator", id, "approve");
        assertThat(projects.getVisible("alice", id).lastReviewedSha()).isEqualTo("a".repeat(40));
    }

    @Test
    void unsupportedStateChangesAndUnsafeRepositoryInputsAreRejected() {
        long id = projects.request("alice", "", "https://gitlab.example.com/team/nested/repository.git", "feature/review");
        assertThat(projects.getVisible("alice", id).provider()).isEqualTo("GITLAB");
        assertThatThrownBy(() -> projects.transition("administrator", id, "pause")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.request("alice", "", "https://evil.example/team/repo", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.request("alice", "", "https://token@github.com/team/repo", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.request("alice", "", "https://github.com/team/repo", "a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.request("alice", "", "https://github.com/team/repo", "a[1]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.list("alice", -1)).isInstanceOf(ResponseStatusException.class);
    }
}
