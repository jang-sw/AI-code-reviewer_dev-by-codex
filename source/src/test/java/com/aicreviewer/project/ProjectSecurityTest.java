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
import java.util.Map;
import java.util.Collections;

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

    @Test
    void projectPagesHaveFiftyVisibleRowsAndKeepStatusSearchAndOwnerScope() {
        for (int i = 0; i < 52; i++) projects.request("alice", "Review team " + i, "https://github.com/team/page-" + i, "");
        projects.request("bob", "Review team private", "https://github.com/other/private", "");
        var first = projects.list("alice", "PENDING", "review TEAM", 0);
        var second = projects.list("alice", "PENDING", "review TEAM", 1);

        assertThat(first.projects()).hasSize(50);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.projects().getFirst().name()).isEqualTo("Review team 51");
        assertThat(second.projects()).extracting(Project::name).containsExactly("Review team 1", "Review team 0");
        assertThat(second.hasNext()).isFalse();
        assertThat(second.status()).isEqualTo("PENDING");
        assertThat(second.query()).isEqualTo("review TEAM");
        assertThat(projects.list("alice", 0)).hasSize(51);
        assertThat(projects.list("alice", "APPROVED", "", 0).projects()).isEmpty();
        assertThat(projects.list("bob", "", "review", 0).projects()).hasSize(1);
        assertThat(projects.list("administrator", "PENDING", "review", 1).projects()).hasSize(3);
    }

    @Test
    void statusAndLiteralNameOrUrlSearchCannotBroadenAccess() {
        long special = projects.request("alice", "Budget 100%_ready!", "https://github.com/team/search-one", "");
        long ordinary = projects.request("alice", "Budget 100ABready", "https://github.com/team/search-two", "");
        projects.request("bob", "Budget 100%_ready!", "https://github.com/private/secret", "");
        projects.transition("administrator", special, "approve");

        assertThat(projects.list("alice", "", "%_", 0).projects()).extracting(Project::id).containsExactly(special);
        assertThat(projects.list("alice", "APPROVED", "BUDGET", 0).projects()).extracting(Project::id).containsExactly(special);
        assertThat(projects.list("alice", "PENDING", "search-two", 0).projects()).extracting(Project::id).containsExactly(ordinary);
        assertThat(projects.list("alice", "", "!", 0).projects()).extracting(Project::id).containsExactly(special);
        assertThat(projects.list("alice", "", "private/secret", 0).projects()).isEmpty();
        assertThat(projects.list("alice", "", "' OR 1=1 --", 0).projects()).isEmpty();
    }

    @Test
    void listRejectsUnboundedAndInvalidFiltersAndReturnsAnEmptyMaximumPage() {
        for (int page : new int[] {-1, 10001, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> projects.list("alice", "", "", page)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> projects.list("alice", page)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> projects.list("alice", "ALL", "", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> projects.list("alice", "", "x".repeat(121), 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> projects.list("alice", "", "line\nbreak", 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(projects.list("alice", "", "", 10000).projects()).isEmpty();
        assertThat(projects.list("alice", "", "", 10000).hasNext()).isFalse();
    }

    @Test
    void displayedReviewStateUsesLatestRunAndDoesNotInventASuccessFromCursor() {
        long id = projects.request("alice", "", "https://github.com/team/review-state", "");
        assertThat(projects.getVisible("alice", id).reviewStatus()).isNull();
        jdbc.update("INSERT INTO review_run(project_id,status) VALUES(?,'SUCCEEDED')", id);
        jdbc.update("INSERT INTO review_run(project_id,status) VALUES(?,'FAILED')", id);
        assertThat(projects.getVisible("alice", id).reviewStatus()).isEqualTo("FAILED");
        assertThat(projects.list("alice", "", "", 0).projects().getFirst().reviewStatus()).isEqualTo("FAILED");
    }

    @Test
    void projectRegistrationRequiresCsrfAndAnApprovedActiveAccount() throws Exception {
        mvc.perform(post("/projects").with(user(details.loadUserByUsername("alice")))
                .param("repositoryUrl", "https://github.com/team/no-csrf")).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET approval_status='PENDING',enabled=FALSE WHERE username='alice'");
        assertThatThrownBy(() -> projects.request("alice", "", "https://github.com/team/not-approved", ""))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project", Long.class)).isZero();
    }

    @Test
    void invalidFieldsAreReportedTogetherAndSafeFormValuesAreRetained() throws Exception {
        var result = mvc.perform(post("/projects").with(user(details.loadUserByUsername("alice"))).with(csrf())
                .param("name", "n".repeat(121)).param("repositoryUrl", "https://github.com/team/valid")
                .param("reviewBranch", "invalid..branch")).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/projects#register");
        assertThat((Map<String, String>) result.getFlashMap().get("projectErrors")).containsKeys("name", "reviewBranch");
        assertThat((Map<String, String>) result.getFlashMap().get("projectForm"))
                .containsEntry("name", "n".repeat(121)).containsEntry("reviewBranch", "invalid..branch")
                .containsEntry("repositoryUrl", "https://github.com/team/valid");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project", Long.class)).isZero();
    }

    @Test
    void credentialOrQueryBearingUrlsNeverSurviveInFlashOrSession() throws Exception {
        for (String url : new String[] { "https://synthetic-secret@github.com/team/repo", "https://github.com/team/repo?token=synthetic-secret",
                "https://github.com/team/repo#synthetic-secret", "https://synthetic-secret%40github.com/team/repo" }) {
            var result = mvc.perform(post("/projects").with(user(details.loadUserByUsername("alice"))).with(csrf())
                    .param("name", "Safe project name").param("repositoryUrl", url).param("reviewBranch", "main"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            assertThat((Map<String, String>) result.getFlashMap().get("projectForm"))
                    .containsEntry("repositoryUrl", "").containsEntry("name", "Safe project name").containsEntry("reviewBranch", "main");
            assertThat(result.getFlashMap().toString()).doesNotContain("synthetic-secret");
            var session = result.getRequest().getSession(false);
            if (session != null) {
                for (String attribute : Collections.list(session.getAttributeNames())) {
                    assertThat(String.valueOf(session.getAttribute(attribute))).doesNotContain("synthetic-secret");
                }
            }
        }
    }

    @Test
    void missingUrlGetsAnInlineFieldErrorAndFilterParametersReachTheModel() throws Exception {
        var result = mvc.perform(post("/projects").with(user(details.loadUserByUsername("alice"))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat((Map<String, String>) result.getFlashMap().get("projectErrors")).containsKey("repositoryUrl");
        var page = mvc.perform(get("/projects").with(user(details.loadUserByUsername("alice")))
                .param("q", " Name ").param("status", "PENDING").param("page", "1"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat(page).containsEntry("query", "Name").containsEntry("filterStatus", "PENDING").containsEntry("page", 1);
    }
}
