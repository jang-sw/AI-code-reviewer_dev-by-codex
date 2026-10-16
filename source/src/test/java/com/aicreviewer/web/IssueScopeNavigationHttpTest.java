package com.aicreviewer.web;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitRepositoryClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real authentication, queries and Jasper; only this dedicated H2 and loopback HTTP are used. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:issue_scope_navigation_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.enabled=false", "app.review.worker-enabled=false",
        "server.address=127.0.0.1", "server.servlet.context-path=/issue-scope-fixture",
        "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IssueScopeNavigationHttpTest {
    private static final String CONTEXT = "/issue-scope-fixture";
    private static final String ADMIN = "scopeadmin";
    private static final String MEMBER = "scopemember";
    private static final String PEER = "scopepeer";
    private static final String PASSWORD = "Synthetic-scope-fixture-4917!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String MEMBER_TITLE = "Member open recommendation";
    private static final String PEER_TITLE = "Peer open recommendation";
    private static final String CLOSED_TITLE = "Peer resolved recommendation";
    private static final List<String> DOMAIN_TABLES = List.of("app_user", "project", "review_run", "review_request",
            "reviewed_commit", "manual_review_file", "review_issue", "git_author_mapping", "audit_event");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    private HttpClient browser;

    @BeforeEach
    void isolatedAccountsAndIssueOwnership() {
        for (String table : List.of("review_issue", "manual_review_file", "reviewed_commit", "review_request",
                "review_run", "project", "git_author_mapping", "audit_event", "app_user", "auth_attempt_bucket")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE auth_attempt_policy SET policy_fingerprint=NULL");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role,enabled,approval_status) "
                + "VALUES(1,?,?,?,'ADMIN',TRUE,'APPROVED'),(2,?,?,?,'USER',TRUE,'APPROVED'),(3,?,?,?,'USER',TRUE,'APPROVED')",
                ADMIN, HASH, ADMIN, MEMBER, HASH, MEMBER, PEER, HASH, PEER);
        jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES "
                + "(11,'Member project','https://github.com/fixture/member','GITHUB','github.com','fixture/member',2,'PAUSED'),"
                + "(12,'Peer project','https://github.com/fixture/peer','GITHUB','github.com','fixture/peer',3,'PAUSED')");
        jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,author_login,summary) VALUES(21,11,?,?,'Member review'),"
                + "(22,12,?,?,'Peer review')", "a".repeat(40), MEMBER, "b".repeat(40), PEER);
        issue(31, 12, 22, 3, PEER_TITLE, "OPEN");
        issue(32, 12, 22, 3, CLOSED_TITLE, "RESOLVED");
        browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void closeOwnedBrowserAndAssertNoExternalCalls() {
        if (browser != null) browser.close();
        verifyNoInteractions(git, ai);
    }

    @ParameterizedTest
    @CsvSource({"USER,0", "USER,1", "ADMIN,0", "ADMIN,1"})
    void homeNavigationAndDefaultIssueListDescribeTheActualRoleScope(String role, int memberOpen) throws Exception {
        if (memberOpen == 1) issue(33, 11, 21, 2, MEMBER_TITLE, "OPEN");
        boolean admin = role.equals("ADMIN");
        login(admin ? ADMIN : MEMBER);
        var before = domainState();
        // A caller-controlled view hint must not determine the authorized role or its wording.
        String html = get("/?adminIssueScope=" + !admin + "&admin=" + !admin + "&isAdmin=" + !admin).body();
        List<String> labels = admin ? List.of("전체 이슈", "전체 미처리 이슈 확인", "전체 미처리 이슈")
                : List.of("내 이슈", "내 미처리 이슈 확인", "미처리 이슈");
        var links = issueLinks(html);
        assertThat(links.stream().map(Link::label)).containsExactlyElementsOf(labels);
        assertThat(html).contains(admin ? "관리자 작업 공간" : "나의 작업 공간")
                .contains(admin ? "전체 프로젝트의 새 변경과 담당자별 수정 권고를 한곳에서 확인하세요."
                        : "프로젝트의 새 변경과 나에게 배정된 수정 권고를 한곳에서 확인하세요.")
                .contains(admin ? "담당자에게 배정된 권고의 근거와 처리 상태를 확인합니다."
                        : "나에게 배정된 권고의 근거를 읽고 처리합니다.");
        if (admin) assertThat(html).doesNotContain("나에게 배정된", "내 미처리 이슈 확인");
        else assertThat(html).doesNotContain("관리자 작업 공간", "전체 미처리 이슈", "Peer project", PEER_TITLE);
        var statistic = Pattern.compile("<article class=\"stat-card\"><a href=\"" + CONTEXT
                + "/issues\">([^<]+)</a><strong>([0-9]+)</strong></article>").matcher(html);
        assertThat(statistic.find()).isTrue();
        assertThat(statistic.group(1)).isEqualTo(labels.getLast());
        assertThat(Integer.parseInt(statistic.group(2))).isEqualTo(memberOpen + (admin ? 1 : 0));
        assertThat(statistic.find()).isFalse();
        for (Link link : links) {
            URI target = URI.create(link.href());
            assertThat(target.isAbsolute()).isFalse();
            assertThat(target.getRawAuthority()).isNull();
            assertThat(target.getPath()).isEqualTo(CONTEXT + "/issues");
            assertThat(target.getRawQuery()).isNull();
            String issues = get(target.getPath().substring(CONTEXT.length())).body();
            assertThat(issues).contains(admin ? "<h1>전체 이슈</h1>" : "<h1>내 이슈</h1>")
                    .contains("href=\"" + CONTEXT + "/issues\" aria-current=\"page\">")
                    .doesNotContain(CLOSED_TITLE);
            if (admin) assertThat(issues).contains(PEER_TITLE, "담당: " + PEER);
            else assertThat(issues).doesNotContain(PEER_TITLE, "담당: " + PEER);
            if (memberOpen == 1) assertThat(issues).contains(MEMBER_TITLE, "담당: " + MEMBER);
            else assertThat(issues).doesNotContain(MEMBER_TITLE);
            if (!admin && memberOpen == 0) assertThat(issues).contains("표시할 이슈가 없습니다");
            assertThat(domainState()).isEqualTo(before);
        }
    }

    private void issue(long id, long project, long commit, long assignee, String title, String status) {
        jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) "
                + "VALUES(?,?,?,?,'MEDIUM',?,'src/Fixture.java','Synthetic recommendation','Review the fixture',?)",
                id, project, commit, assignee, title, status);
    }

    private void login(String username) throws Exception {
        String html = get("/login").body();
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        var response = browser.send(HttpRequest.newBuilder(uri("/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(username) + "&password=" + encode(PASSWORD)
                        + "&_csrf=" + encode(token.group(1)))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(uri("/").resolve(response.headers().firstValue("Location").orElseThrow())).isEqualTo(uri("/"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        var response = browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private record Link(String label, String href) { }

    private static List<Link> issueLinks(String html) {
        return Pattern.compile("<a\\b[^>]*href=\"(" + CONTEXT + "/issues)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
                .matcher(html).results().map(match -> new Link(match.group(2).replaceAll("<[^>]+>", "").strip(), match.group(1))).toList();
    }

    private Map<String, List<Map<String, Object>>> domainState() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : DOMAIN_TABLES) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + (table.equals("review_request") ? "project_id" : "id")));
        }
        return result;
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + CONTEXT + path); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
