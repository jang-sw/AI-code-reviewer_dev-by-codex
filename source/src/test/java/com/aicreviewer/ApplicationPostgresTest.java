package com.aicreviewer;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.project.ProjectService;
import com.aicreviewer.review.ProjectReviewLock;
import com.aicreviewer.review.ReviewCoordinator;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL, embedded Tomcat/Jasper, session login, CSRF, permissions and durable review storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class ApplicationPostgresTest {
    private static final String PASSWORD = "Only-for-integration-7921!";
    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        registry.add("app.bootstrap.username", () -> "pgadmin");
        registry.add("app.bootstrap.password", () -> PASSWORD);
        registry.add("app.bootstrap.git-username", () -> "pgadmin");
        registry.add("app.review.enabled", () -> false);
        registry.add("app.git.allowed-hosts", () -> "github.com,gitlab.example.test");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService users;
    @Autowired ProjectService projects;
    @Autowired ReviewCoordinator reviews;
    @Autowired ProjectReviewLock locks;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    String writer;
    String outsider;
    long projectId;

    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        writer = "writer" + suffix;
        outsider = "other" + suffix;
        users.create("pgadmin", writer, PASSWORD, writer, "USER");
        users.create("pgadmin", outsider, PASSWORD, outsider, "USER");
        projectId = projects.request(writer, "프로젝트 <script>alert(1)</script>", "https://github.com/example/repo-" + suffix, "");
    }

    @Test
    void rendersJspAndProtectsOwnershipAdminAndCsrf() throws Exception {
        HttpClient anonymous = client();
        assertThat(get(anonymous, "/projects").statusCode()).isEqualTo(302);
        HttpResponse<String> login = get(anonymous, "/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("lang=\"ko\"", "name=\"_csrf\"", "로그인", "계정 생성", "AI 리뷰는 수정 권고입니다.");
        assertThat(login.headers().firstValue("Content-Security-Policy")).isPresent();
        HttpClient ownerSession = login(writer);
        HttpResponse<String> detail = get(ownerSession, "/projects/" + projectId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>");
        assertThat(get(ownerSession, "/admin/users").statusCode()).isEqualTo(403);
        assertThat(post(ownerSession, "/admin/projects/" + projectId + "/approve", Map.of("_csrf", csrf(detail.body()))).statusCode()).isEqualTo(403);
        assertThat(post(ownerSession, "/projects", Map.of("repositoryUrl", "https://github.com/example/csrf")).statusCode()).isEqualTo(403);
        assertThat(post(ownerSession, "/projects/" + projectId + "/review", Map.of("_csrf", csrf(detail.body()))).statusCode()).isEqualTo(409);
        HttpClient unrelatedSession = login(outsider);
        assertThat(post(unrelatedSession, "/projects/" + projectId + "/review", Map.of("_csrf", csrf(get(unrelatedSession, "/issues").body()))).statusCode()).isEqualTo(403);
        assertThat(get(login(outsider), "/projects/" + projectId).statusCode()).isEqualTo(404);
        assertThat(get(ownerSession, "/issues").statusCode()).isEqualTo(200);
        assertThat(get(ownerSession, "/reviews?projectId=" + projectId).statusCode()).isEqualTo(200);
        assertThat(get(ownerSession, "/account/password").statusCode()).isEqualTo(200);
        assertThat(get(ownerSession, "/").statusCode()).isEqualTo(200);
        assertThat(get(login("pgadmin"), "/admin/users").statusCode()).isEqualTo(200);
        assertThat(get(login("pgadmin"), "/admin/audit").statusCode()).isEqualTo(200);
        assertThat(get(ownerSession, "/admin/audit").statusCode()).isEqualTo(403);
    }

    @Test
    void durableBatchRetryAssignsIssuesAndDoesNotDuplicate() throws Exception {
        projects.transition("pgadmin", projectId, "approve");
        String firstSha = "a".repeat(40), secondSha = "b".repeat(40);
        GitCommit first = new GitCommit(firstSha, writer, "first", "diff --git a/A.java b/A.java\n@@ -0,0 +1 @@\n+bad();");
        GitCommit second = new GitCommit(secondSha, null, "second", "diff --git a/B.java b/B.java\n@@ -0,0 +1 @@\n+bad();");
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(ai.review(first)).thenReturn(finding("A.java"));
        when(ai.review(second)).thenThrow(new IllegalStateException("provider secret must not be persisted"));
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(count("reviewed_commit")).isEqualTo(1);
        assertThat(cursor()).isNull();
        assertThat(jdbc.queryForObject("select error_message from review_run where project_id=? order by id desc limit 1", String.class, projectId))
                .doesNotContain("provider secret");
        doReturn(finding("B.java")).when(ai).review(second);
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(cursor()).isEqualTo(secondSha);
        assertThat(count("reviewed_commit")).isEqualTo(2);
        assertThat(count("review_issue")).isEqualTo(2);
        verify(ai, times(1)).review(first);
        long writerId = jdbc.queryForObject("select id from app_user where username=?", Long.class, writer);
        assertThat(jdbc.queryForList("select assignee_id from review_issue where project_id=?", Long.class, projectId))
                .containsOnly(writerId);
        HttpClient ownerSession = login(writer);
        HttpResponse<String> ownerIssues = get(ownerSession, "/issues");
        assertThat(ownerIssues.statusCode()).isEqualTo(200);
        assertThat(ownerIssues.body()).contains("테스트 권고", "&lt;script&gt;").doesNotContain("<script>bad</script>");
        HttpClient unrelatedSession = login(outsider);
        HttpResponse<String> unrelatedIssues = get(unrelatedSession, "/issues");
        assertThat(unrelatedIssues.body()).doesNotContain("테스트 권고");
        long issueId = jdbc.queryForObject("select min(id) from review_issue where project_id=?", Long.class, projectId);
        String statusPath = "/issues/" + issueId + "/status";
        assertThat(post(unrelatedSession, statusPath, Map.of("_csrf", csrf(unrelatedIssues.body()), "status", "RESOLVED")).statusCode()).isEqualTo(404);
        assertThat(post(ownerSession, statusPath, Map.of("status", "RESOLVED")).statusCode()).isEqualTo(403);
        assertThat(post(ownerSession, statusPath, Map.of("_csrf", csrf(ownerIssues.body()), "status", "RESOLVED")).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("select status from review_issue where id=?", String.class, issueId)).isEqualTo("RESOLVED");
        assertThat(get(ownerSession, "/issues").body()).doesNotContain(statusPath);
        assertThat(get(ownerSession, "/issues?status=").body()).contains(statusPath);
        HttpClient adminSession = login("pgadmin");
        assertThat(post(adminSession, statusPath, Map.of("_csrf", csrf(get(adminSession, "/issues").body()), "status", "DISMISSED")).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("select status from review_issue where id=?", String.class, issueId)).isEqualTo("DISMISSED");
    }

    @Test
    void advisoryLockExcludesSecondWorkerAndReleases() {
        projects.transition("pgadmin", projectId, "approve");
        try (var lease = locks.tryAcquire(projectId).orElseThrow()) {
            assertThat(locks.tryAcquire(projectId)).isEmpty();
            assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.BUSY);
            verifyNoInteractions(git, ai);
        }
        try (var lease = locks.tryAcquire(projectId).orElseThrow()) {
            assertThat(lease).isNotNull();
        }
    }

    @Test
    void disablingAccountInvalidatesExistingSession() throws Exception {
        HttpClient session = login(writer);
        assertThat(get(session, "/projects").statusCode()).isEqualTo(200);
        long writerId = jdbc.queryForObject("select id from app_user where username=?", Long.class, writer);
        users.setEnabled("pgadmin", writerId, false);
        assertThat(get(session, "/projects").statusCode()).isEqualTo(302);
    }

    @Test
    void manualPostDispatchesBackgroundReviewToDurableCompletion() throws Exception {
        projects.transition("pgadmin", projectId, "approve");
        GitCommit commit = new GitCommit("d".repeat(40), writer, "background", "diff --git a/A.java b/A.java\n@@ -0,0 +1 @@\n+bad();");
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(commit));
        when(ai.review(commit)).thenReturn(finding("A.java"));
        HttpClient session = login(writer);
        String token = csrf(get(session, "/projects/" + projectId).body());
        var response = post(session, "/projects/" + projectId + "/review", Map.of("_csrf", token));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).endsWith("/reviews?projectId=" + projectId);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(cursor()).isEqualTo(commit.sha());
            assertThat(jdbc.queryForObject("select status from review_run where project_id=? order by id desc limit 1", String.class, projectId)).isEqualTo("SUCCEEDED");
        });
        assertThat(count("review_issue")).isEqualTo(1);
    }

    @Test
    void postgresConstraintFailureRollsBackCommitIssuesAndCount() {
        projects.transition("pgadmin", projectId, "approve");
        GitCommit commit = new GitCommit("e".repeat(40), writer, "atomic", "diff --git a/A.java b/A.java\n@@ -0,0 +1 @@\n+bad();");
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(commit));
        when(ai.review(commit)).thenReturn(new ReviewResult("rollback fixture", List.of(new ReviewFinding("HIGH", "PG_ROLLBACK_TEST", "A.java", 1, "test description", "test suggestion"))));
        jdbc.execute("alter table review_issue add constraint it_reject_marker check (title <> 'PG_ROLLBACK_TEST')");
        try {
            assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
            assertThat(count("reviewed_commit")).isZero();
            assertThat(count("review_issue")).isZero();
            assertThat(cursor()).isNull();
            assertThat(jdbc.queryForObject("select reviewed_commits from review_run where project_id=? order by id desc limit 1", Integer.class, projectId)).isZero();
        } finally {
            jdbc.execute("alter table review_issue drop constraint it_reject_marker");
        }
        doReturn(finding("A.java")).when(ai).review(commit);
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(count("review_issue")).isEqualTo(1);
    }

    @Test
    void encodedLoginPathCannotBypassAttemptLimit() throws Exception {
        HttpClient session = client();
        String token = csrf(get(session, "/login").body());
        for (int attempt = 0; attempt < 10; attempt++) {
            var response = post(session, "/log%69n", Map.of("username", writer, "password", "wrong-password", "_csrf", token));
            assertThat(response.statusCode()).isEqualTo(302);
        }
        var limited = post(session, "/log%69n", Map.of("username", writer, "password", "wrong-password", "_csrf", token));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void adminAuthorMappingAssignsGitlabIssuesWithoutGrantingProjectAccess() throws Exception {
        long authorId = jdbc.queryForObject("select id from app_user where username=?", Long.class, outsider);
        String email = outsider + "@example.test";
        String origin = "https://gitlab.example.test";
        long gitlabProject = projects.request(writer, "GitLab 작성자 배정", origin + "/team/" + writer, "");
        projects.transition("pgadmin", gitlabProject, "approve");
        HttpClient ownerSession = login(writer);
        HttpClient authorSession = login(outsider);
        HttpClient adminSession = login("pgadmin");
        assertThat(get(ownerSession, "/admin/git-authors").statusCode()).isEqualTo(403);
        assertThat(post(ownerSession, "/admin/git-authors", Map.of("_csrf", csrf(get(ownerSession, "/issues").body()),
                "userId", Long.toString(authorId), "repositoryOrigin", origin, "authorEmail", email)).statusCode()).isEqualTo(403);
        assertThat(post(adminSession, "/admin/git-authors", Map.of("userId", Long.toString(authorId),
                "repositoryOrigin", origin, "authorEmail", email)).statusCode()).isEqualTo(403);
        var mappingPage = get(adminSession, "/admin/git-authors");
        assertThat(mappingPage.statusCode()).isEqualTo(200);
        String token = csrf(mappingPage.body());
        assertThat(post(adminSession, "/admin/git-authors", Map.of("_csrf", token, "userId", Long.toString(authorId),
                "repositoryOrigin", origin.toUpperCase() + ":443/", "authorEmail", " " + email.toUpperCase() + " ")).statusCode()).isEqualTo(302);
        long mappingId = jdbc.queryForObject("select id from git_author_mapping where repository_origin=? and author_email=?", Long.class, origin, email);
        assertThat(get(adminSession, "/admin/git-authors").body()).contains(email);
        GitCommit first = new GitCommit("1".repeat(40), writer, email, "GitLab claimed author", "diff --git a/A.java b/A.java\n@@ -0,0 +1 @@\n+bad();");
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(first));
        when(ai.review(first)).thenReturn(finding("A.java"));
        assertThat(reviews.reviewProject(gitlabProject, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        long issueId = jdbc.queryForObject("select id from review_issue where project_id=?", Long.class, gitlabProject);
        assertThat(jdbc.queryForObject("select assignee_id from review_issue where id=?", Long.class, issueId)).isEqualTo(authorId);
        assertThat(jdbc.queryForObject("select assignment_reason from review_issue where id=?", String.class, issueId)).isEqualTo("GIT_EMAIL_MAPPING");
        assertThat(get(authorSession, "/issues").body()).contains("/issues/" + issueId + "/status");
        assertThat(get(ownerSession, "/issues").body()).doesNotContain("/issues/" + issueId + "/status");
        assertThat(get(authorSession, "/projects/" + gitlabProject).statusCode()).isEqualTo(404);
        assertThat(post(adminSession, "/admin/git-authors/" + mappingId + "/delete", Map.of("_csrf", token)).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("select count(*) from git_author_mapping where id=?", Integer.class, mappingId)).isZero();
        GitCommit second = new GitCommit("2".repeat(40), outsider, email, "Deleted mapping", "diff --git a/B.java b/B.java\n@@ -0,0 +1 @@\n+bad();");
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(second));
        when(ai.review(second)).thenReturn(finding("B.java"));
        assertThat(reviews.reviewProject(gitlabProject, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(jdbc.queryForObject("select assignment_reason from review_issue where project_id=? order by id desc limit 1", String.class, gitlabProject))
                .isEqualTo("PROJECT_OWNER_FALLBACK");
        assertThat(jdbc.queryForObject("select assignee_id from review_issue where id=?", Long.class, issueId)).isEqualTo(authorId);
    }

    private ReviewResult finding(String file) {
        return new ReviewResult("커밋 검토 결과", List.of(new ReviewFinding("HIGH", "테스트 권고", file, 1,
                "<script>bad</script> 수정이 필요합니다.", "입력을 검증하세요.")));
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, projectId); }
    private String cursor() { return jdbc.queryForObject("select last_reviewed_sha from project where id=?", String.class, projectId); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    private HttpClient login(String username) throws Exception {
        HttpClient client = client();
        String token = csrf(get(client, "/login").body());
        HttpResponse<String> response = post(client, "/login", Map.of("username", username, "password", PASSWORD, "_csrf", token));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).doesNotContain("error");
        return client;
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> fields) throws Exception {
        String data = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String csrf(String html) {
        var matcher = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).as("rendered CSRF form token").isTrue();
        return matcher.group(1);
    }
}
