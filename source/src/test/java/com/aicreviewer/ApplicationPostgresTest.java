package com.aicreviewer;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
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
import java.util.Set;
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
        assertThat(login.body()).contains("lang=\"ko\"", "name=\"_csrf\"", "로그인", "/signup", "관리자", "AI 리뷰는 수정 권고입니다.");
        assertThat(login.headers().firstValue("Content-Security-Policy")).isPresent();
        HttpClient ownerSession = login(writer);
        HttpResponse<String> detail = get(ownerSession, "/projects/" + projectId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>");
        assertThat(detail.body()).contains("자동 리뷰 꺼짐").doesNotContain("매시간 새로운 변경", "자동 리뷰가 켜져");
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
        stubBatch(List.of(first, second), secondSha);
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
        assertThat(ownerIssues.body()).contains("테스트 권고", "&lt;script&gt;", "상태 저장", "처리 상태").doesNotContain("<script>bad</script>");
        String repositoryUrl = jdbc.queryForObject("select repository_url from project where id=?", String.class, projectId);
        assertThat(ownerIssues.body()).contains(repositoryUrl + "/commit/" + firstSha, "rel=\"noopener noreferrer\"");
        HttpClient unrelatedSession = login(outsider);
        HttpResponse<String> unrelatedIssues = get(unrelatedSession, "/issues");
        assertThat(unrelatedIssues.body()).doesNotContain("테스트 권고");
        long issueId = jdbc.queryForObject("select min(id) from review_issue where project_id=?", Long.class, projectId);
        var issueDetail = get(ownerSession, "/issues/" + issueId);
        assertThat(issueDetail.statusCode()).isEqualTo(200);
        assertThat(issueDetail.body()).contains("수정 권고", "입력을 검증하세요.", "&lt;script&gt;bad&lt;/script&gt;")
                .doesNotContain("<script>bad</script>");
        assertThat(get(unrelatedSession, "/issues/" + issueId).statusCode()).isEqualTo(404);
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
    void publicSignupRequiresAdminApprovalBeforeLoginAndProjectRegistration() throws Exception {
        String applicant = "join" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpClient anonymous = client();
        var signup = get(anonymous, "/signup");
        assertThat(signup.statusCode()).isEqualTo(200);
        assertThat(signup.body()).contains("회원가입", "confirmPassword", "gitUsername");
        Map<String, String> fields = Map.of("username", applicant, "password", PASSWORD, "confirmPassword", PASSWORD,
                "gitUsername", applicant, "role", "ADMIN", "enabled", "true", "approvalStatus", "APPROVED");
        assertThat(post(anonymous, "/signup", fields).statusCode()).isEqualTo(403);
        var signedFields = new java.util.HashMap<>(fields);
        signedFields.put("_csrf", csrf(signup.body()));
        var submitted = post(anonymous, "/signup", signedFields);
        assertThat(submitted.statusCode()).isEqualTo(302);
        assertThat(submitted.headers().firstValue("location").orElse("")).endsWith("/signup?submitted");
        assertThat(post(anonymous, "/signup", signedFields).headers().firstValue("location"))
                .isEqualTo(submitted.headers().firstValue("location"));
        var account = jdbc.queryForMap("select id, role, enabled, approval_status from app_user where username=?", applicant);
        assertThat(account).containsEntry("role", "USER").containsEntry("enabled", false).containsEntry("approval_status", "PENDING");
        long id = ((Number) account.get("id")).longValue();
        assertThat(post(anonymous, "/login", Map.of("username", applicant, "password", PASSWORD,
                "_csrf", csrf(get(anonymous, "/login").body()))).headers().firstValue("location").orElse("")).endsWith("/login?error");
        assertThat(get(anonymous, "/projects").statusCode()).isEqualTo(302);
        HttpClient ordinary = login(writer);
        assertThat(post(ordinary, "/admin/users/" + id + "/approve", Map.of("_csrf", csrf(get(ordinary, "/issues").body()))).statusCode()).isEqualTo(403);
        HttpClient admin = login("pgadmin");
        var queue = get(admin, "/admin/users?search=" + applicant);
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains(applicant, "승인 대기");
        assertThat(post(admin, "/admin/users/" + id + "/approve", Map.of("_csrf", csrf(queue.body()))).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForMap("select enabled, approval_status from app_user where id=?", id))
                .containsEntry("enabled", true).containsEntry("approval_status", "APPROVED");
        assertThat(get(admin, "/admin/users?status=&search=" + applicant).body()).contains(applicant, "승인 완료");
        HttpClient approved = login(applicant);
        var projectsPage = get(approved, "/projects");
        assertThat(projectsPage.statusCode()).isEqualTo(200);
        var registration = post(approved, "/projects", Map.of("_csrf", csrf(projectsPage.body()),
                "repositoryUrl", "https://github.com/example/" + applicant));
        assertThat(registration.statusCode()).isEqualTo(302);
        long created = jdbc.queryForObject("select id from project where owner_id=?", Long.class, id);
        assertThat(jdbc.queryForObject("select status from project where id=?", String.class, created)).isEqualTo("PENDING");
        String projectToken = csrf(get(approved, "/projects/" + created).body());
        assertThat(post(approved, "/projects/" + created + "/review", Map.of("_csrf", projectToken)).statusCode()).isEqualTo(409);
        assertThat(post(admin, "/admin/projects/" + created + "/approve", Map.of("_csrf", csrf(get(admin, "/projects/" + created).body()))).statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForObject("select status from project where id=?", String.class, created)).isEqualTo("APPROVED");
        assertThat(get(approved, "/admin/users").statusCode()).isEqualTo(403);
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
        stubBatch(List.of(commit), commit.sha());
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
        stubBatch(List.of(commit), commit.sha());
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
        stubBatch(List.of(first), first.sha());
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
        stubBatch(List.of(second), second.sha());
        when(ai.review(second)).thenReturn(finding("B.java"));
        assertThat(reviews.reviewProject(gitlabProject, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(jdbc.queryForObject("select assignment_reason from review_issue where project_id=? order by id desc limit 1", String.class, gitlabProject))
                .isEqualTo("PROJECT_OWNER_FALLBACK");
        assertThat(jdbc.queryForObject("select assignee_id from review_issue where id=?", Long.class, issueId)).isEqualTo(authorId);
    }

    @Test
    void verifiedEmptyAndMetadataOnlyCommitsPersistExplicitCoverageWithoutAiClaims() throws Exception {
        projects.transition("pgadmin", projectId, "approve");
        GitCommit empty = new GitCommit("3".repeat(40), writer, null, "empty", "", "EMPTY", "파일 변경 없음");
        GitCommit metadata = new GitCommit("4".repeat(40), writer, null, "metadata", "", "METADATA_ONLY",
                "<script>alert(1)</script> 경로 및 실행권한 변경: 100644 -> 100755");
        stubBatch(List.of(empty, metadata), metadata.sha());
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        verifyNoInteractions(ai);
        assertThat(cursor()).isEqualTo(metadata.sha());
        assertThat(count("reviewed_commit")).isEqualTo(2);
        assertThat(count("review_issue")).isZero();
        assertThat(jdbc.queryForList("select coverage_type from reviewed_commit where project_id=? order by id", String.class, projectId))
                .containsExactly("EMPTY", "METADATA_ONLY");
        var response = get(login(writer), "/reviews?projectId=" + projectId);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("AI 본문 검토 없음", "수동 확인 필요", "&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void paginatesBothHistoriesIndependentlyThroughRenderedJsp() throws Exception {
        for (int index = 0; index < 52; index++) {
            jdbc.update("insert into review_run(project_id,status,error_message) values (?,'FAILED',?)", projectId,
                    "RUN_" + String.format("%02d", index) + "_END");
            jdbc.update("insert into reviewed_commit(project_id,commit_sha,summary) values (?,?,?)", projectId,
                    String.format("%040x", index + 1), "COMMIT_" + String.format("%02d", index) + "_END");
        }
        HttpClient owner = login(writer);
        String path = "/reviews?projectId=" + projectId;
        var firstPage = get(owner, path);
        assertThat(firstPage.statusCode()).isEqualTo(200);
        assertThat(firstPage.body()).contains("RUN_51_END", "COMMIT_51_END", "원본 커밋 보기")
                .doesNotContain("RUN_00_END", "COMMIT_00_END");
        var olderCommits = get(owner, path + "&commitPage=1");
        assertThat(olderCommits.statusCode()).isEqualTo(200);
        assertThat(olderCommits.body()).contains("COMMIT_00_END", "COMMIT_01_END", "RUN_51_END",
                "commitPage=1&amp;runPage=1#runs").doesNotContain("COMMIT_51_END", "RUN_00_END");
        var olderRuns = get(owner, path + "&runPage=1");
        assertThat(olderRuns.statusCode()).isEqualTo(200);
        assertThat(olderRuns.body()).contains("RUN_00_END", "RUN_01_END", "COMMIT_51_END",
                "commitPage=1&amp;runPage=1#commits").doesNotContain("RUN_51_END", "COMMIT_00_END");
        assertThat(get(owner, path + "&commitPage=-1").statusCode()).isEqualTo(400);
        assertThat(get(owner, path + "&runPage=10001").statusCode()).isEqualTo(400);
        assertThat(get(login(outsider), path + "&commitPage=1").statusCode()).isEqualTo(403);
    }

    @Test
    void partialMergeProgressAndEmptyRecoveryAdvanceOnlyExplicitCheckpoint() {
        projects.transition("pgadmin", projectId, "approve");
        GitCommit first = new GitCommit("5".repeat(40), writer, null, "partial merge", "", "EMPTY", "파일 변경 없음");
        stubBatch(List.of(first), null);
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(cursor()).isNull();
        assertThat(count("reviewed_commit")).isEqualTo(1);
        // Simulate resuming after commit persistence but before safe checkpoint persistence.
        stubBatch(List.of(), first.sha());
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(cursor()).isEqualTo(first.sha());
        assertThat(count("reviewed_commit")).isEqualTo(1);
        verify(git).batch(any(), any(), isNull(), eq(Set.of(first.sha())), anyInt());
        verifyNoInteractions(ai);
    }

    @Test
    void adminRecoversPausedHistoryThroughHttpWithoutDeletingReviewsOrIssues() throws Exception {
        projects.transition("pgadmin", projectId, "approve");
        GitCommit original = new GitCommit("6".repeat(40), writer, "before rewrite",
                "diff --git a/A.java b/A.java\n@@ -0,0 +1 @@\n+bad();");
        stubBatch(List.of(original), original.sha());
        when(ai.review(original)).thenReturn(finding("A.java"));
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        long issueId = jdbc.queryForObject("select id from review_issue where project_id=?", Long.class, projectId);
        jdbc.update("update review_issue set status='RESOLVED' where id=?", issueId);
        String repositoryUrl = jdbc.queryForObject("select repository_url from project where id=?", String.class, projectId);
        String path = "/admin/projects/" + projectId + "/review-progress/reset";
        HttpClient admin = login("pgadmin");
        HttpClient owner = login(writer);
        Map<String, String> fields = new java.util.HashMap<>(Map.of("repositoryUrl", repositoryUrl,
                "expectedCursor", original.sha(), "reason", "이력 재작성 확인 후 복구"));
        assertThat(post(admin, path, fields).statusCode()).isEqualTo(403);
        fields.put("_csrf", csrf(get(owner, "/projects/" + projectId).body()));
        assertThat(post(owner, path, fields).statusCode()).isEqualTo(403);
        fields.put("_csrf", csrf(get(admin, "/projects/" + projectId).body()));
        assertThat(post(admin, path, fields).statusCode()).isEqualTo(409);
        projects.transition("pgadmin", projectId, "pause");
        var paused = get(admin, "/projects/" + projectId);
        assertThat(paused.statusCode()).isEqualTo(200);
        assertThat(paused.body()).contains(path, "name=\"expectedCursor\"", "name=\"reason\"", "name=\"repositoryUrl\"");
        assertThat(get(owner, "/projects/" + projectId).body()).doesNotContain(path);
        fields.put("repositoryUrl", repositoryUrl + "-wrong");
        var wrongRepository = post(admin, path, fields);
        assertThat(wrongRepository.statusCode()).isEqualTo(400);
        assertThat(wrongRepository.body()).contains("프로젝트 주소와 일치하지 않습니다", "프로젝트로 돌아가기")
                .doesNotContain(repositoryUrl + "-wrong", "이력 재작성 확인 후 복구");
        fields.put("repositoryUrl", repositoryUrl);
        fields.put("expectedCursor", "7".repeat(40));
        assertThat(post(admin, path, fields).statusCode()).isEqualTo(409);
        fields.put("expectedCursor", original.sha());
        try (var lease = locks.tryAcquire(projectId).orElseThrow()) {
            var busy = post(admin, path, fields);
            assertThat(busy.statusCode()).isEqualTo(409);
            assertThat(busy.body()).contains("리뷰가 실행 중이어서", "새로고침", "프로젝트로 돌아가기")
                    .doesNotContain("이력 재작성 확인 후 복구");
            assertThat(cursor()).isEqualTo(original.sha());
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='PROJECT_REVIEW_PROGRESS_RESET' and target_id=?",
                Integer.class, projectId)).isZero();
        jdbc.execute("alter table audit_event add constraint it_recovery_audit_failure check (action <> 'PROJECT_REVIEW_PROGRESS_RESET' OR target_id <> " + projectId + ")");
        try {
            assertThat(post(admin, path, fields).statusCode()).isEqualTo(500);
            assertThat(cursor()).isEqualTo(original.sha());
            assertThat(count("reviewed_commit")).isEqualTo(1);
            assertThat(count("review_issue")).isEqualTo(1);
        } finally {
            jdbc.execute("alter table audit_event drop constraint it_recovery_audit_failure");
        }
        var reset = post(admin, path, fields);
        assertThat(reset.statusCode()).isEqualTo(302);
        assertThat(reset.headers().firstValue("location").orElse("")).endsWith("/projects/" + projectId);
        assertThat(cursor()).isNull();
        assertThat(jdbc.queryForObject("select status from project where id=?", String.class, projectId)).isEqualTo("PAUSED");
        assertThat(count("reviewed_commit")).isEqualTo(1);
        assertThat(count("review_issue")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from review_issue where id=?", String.class, issueId)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select detail from audit_event where action='PROJECT_REVIEW_PROGRESS_RESET' and target_id=?",
                String.class, projectId)).contains(original.sha(), "이력 재작성 확인 후 복구");
        assertThat(post(admin, path, fields).statusCode()).isEqualTo(409);
        projects.transition("pgadmin", projectId, "approve");
        GitCommit replacement = new GitCommit("8".repeat(40), writer, "rewritten branch",
                "diff --git a/B.java b/B.java\n@@ -0,0 +1 @@\n+bad();");
        stubBatch(List.of(original, replacement), replacement.sha());
        when(ai.review(replacement)).thenReturn(finding("B.java"));
        assertThat(reviews.reviewProject(projectId, writer)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        verify(git).batch(any(), any(), isNull(), eq(Set.of(original.sha())), anyInt());
        verify(ai, times(1)).review(original);
        verify(ai, times(1)).review(replacement);
        assertThat(cursor()).isEqualTo(replacement.sha());
        assertThat(count("reviewed_commit")).isEqualTo(2);
        assertThat(count("review_issue")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from review_issue where id=?", String.class, issueId)).isEqualTo("RESOLVED");
    }

    private void stubBatch(List<GitCommit> commits, String checkpoint) {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> completed = invocation.getArgument(3);
            return new GitReviewBatch(commits.stream().filter(commit -> !completed.contains(commit.sha())).toList(), checkpoint);
        });
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
