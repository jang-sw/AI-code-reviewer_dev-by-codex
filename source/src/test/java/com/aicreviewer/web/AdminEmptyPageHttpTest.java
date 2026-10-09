package com.aicreviewer.web;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.identity.AccountUserDetailsService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Jasper rendering plus MVC mutations, with only this class's in-memory data and loopback HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_empty_page_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=true", "app.bootstrap.username=emptypageadmin",
        "app.bootstrap.password=Empty-page-fixture-4318!", "app.bootstrap.git-username=emptypageadmin",
        "app.review.enabled=false", "app.review.worker-enabled=false",
        "server.address=127.0.0.1", "server.servlet.context-path=/empty-page-fixture",
        "server.servlet.session.cookie.secure=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminEmptyPageHttpTest {
    private static final String CONTEXT = "/empty-page-fixture";
    private static final String ADMIN = "emptypageadmin";
    private static final String PASSWORD = "Empty-page-fixture-4318!";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AccountUserDetailsService details;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    private long administratorId;
    private HttpClient browser;

    @BeforeEach
    void syntheticDataOnly() throws Exception {
        // This dedicated H2 URL cannot use TEST_DATABASE_URL or any existing PostgreSQL database.
        jdbc.update("DELETE FROM git_author_mapping");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user WHERE username <> ?", ADMIN);
        jdbc.update("DELETE FROM auth_attempt_bucket");
        administratorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, ADMIN);
        browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String loginPage = httpGet("/login").body();
        var csrfToken = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage);
        assertThat(csrfToken.find()).isTrue();
        var response = browser.send(HttpRequest.newBuilder(uri("/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(ADMIN) + "&password=" + encode(PASSWORD)
                        + "&_csrf=" + encode(csrfToken.group(1)))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
    }

    @AfterEach
    void closeSyntheticBrowser() {
        if (browser != null) browser.close();
        verifyNoInteractions(git, ai);
    }

    @Test
    void deletingLastMappingOnSecondPageKeepsRemainingMappingsAndOffersFilteredRecovery() throws Exception {
        for (int i = 0; i < 51; i++) {
            jdbc.update("INSERT INTO git_author_mapping(user_id,repository_origin,author_email) VALUES(?,?,?)",
                    administratorId, "https://github.com", "fixture" + i + "@example.invalid");
        }
        var second = mvc.perform(adminGet("/admin/git-authors").param("page", "1").param("userSearch", ADMIN))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) second.get("mappings")).hasSize(1);
        long lastId = jdbc.queryForObject("SELECT MIN(id) FROM git_author_mapping", Long.class);
        mvc.perform(adminPost("/admin/git-authors/" + lastId + "/delete").param("page", "1").param("userSearch", ADMIN))
                .andExpect(redirectedUrl(CONTEXT + "/admin/git-authors?page=1&userSearch=" + ADMIN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM git_author_mapping", Long.class)).isEqualTo(50);

        String html = httpGet("/admin/git-authors?page=1&userSearch=" + ADMIN).body();
        assertThat(html).contains("이 페이지에 표시할 매핑이 없습니다", ">이전</a>")
                .doesNotContain("등록된 매핑이 없습니다");
        assertRecoveryLink(html, "mappings-first-page", "/admin/git-authors", Map.of("page", "0", "userSearch", ADMIN));
        String first = followRecovery(html, "mappings-first-page");
        assertThat(first).contains("fixture50@example.invalid").doesNotContain("이 페이지에 표시할 매핑이 없습니다");
    }

    @Test
    void approvingLastPendingUserOnSecondPageKeepsApprovalFilterAndSearchInRecovery() throws Exception {
        for (int i = 0; i < 51; i++) {
            String name = "pending_" + String.format(java.util.Locale.ROOT, "%03d", i);
            jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled,approval_status) VALUES(?,'unused-synthetic-hash',?,'USER',FALSE,'PENDING')",
                    name, name);
        }
        var second = mvc.perform(adminGet("/admin/users").param("page", "1").param("status", "PENDING").param("search", "pending_"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) second.get("users")).hasSize(1);
        long lastId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='pending_000'", Long.class);
        mvc.perform(adminPost("/admin/users/" + lastId + "/approve").param("page", "1").param("status", "PENDING").param("search", "pending_"))
                .andExpect(redirectedUrl(CONTEXT + "/admin/users?status=PENDING&search=pending_&page=1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE approval_status='PENDING'", Long.class)).isEqualTo(50);

        String html = httpGet("/admin/users?page=1&status=PENDING&search=pending_").body();
        assertThat(html).contains("이 페이지에 표시할 사용자가 없습니다", ">이전</a>")
                .doesNotContain("조회 조건에 맞는 사용자가 없습니다");
        assertRecoveryLink(html, "users-first-page", "/admin/users", Map.of("page", "0", "status", "PENDING", "search", "pending_"));
        String first = followRecovery(html, "users-first-page");
        assertThat(first).contains("pending_050").doesNotContain("pending_000", "이 페이지에 표시할 사용자가 없습니다");
    }

    @Test
    void auditPageBeyondExistingHistoryDoesNotClaimThereHaveBeenNoEvents() throws Exception {
        jdbc.update("INSERT INTO audit_event(actor_id,action,target_type,target_id,detail) VALUES(?,'EMPTY_PAGE_FIXTURE','USER',?,'Synthetic retained event')",
                administratorId, administratorId);
        var model = mvc.perform(adminGet("/admin/audit").param("page", "1"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat((List<?>) model.get("events")).isEmpty();
        assertThat(model).containsEntry("page", 1).containsEntry("hasNext", false);

        String html = httpGet("/admin/audit?page=1").body();
        assertThat(html).contains("이 페이지에 표시할 감사 기록이 없습니다", ">이전</a>").doesNotContain("아직 기록이 없습니다");
        assertRecoveryLink(html, "audit-first-page", "/admin/audit", Map.of("page", "0"));
        assertThat(followRecovery(html, "audit-first-page")).contains("Synthetic retained event")
                .doesNotContain("이 페이지에 표시할 감사 기록이 없습니다");
    }

    @ParameterizedTest
    @CsvSource({
            "/admin/git-authors,등록된 매핑이 없습니다,mappings-first-page",
            "/admin/users?status=PENDING,조회 조건에 맞는 사용자가 없습니다,users-first-page",
            "/admin/audit,아직 기록이 없습니다,audit-first-page"
    })
    void genuinelyEmptyFirstPageKeepsItsOriginalExplanation(String path, String message, String recoveryId) throws Exception {
        String html = httpGet(path).body();
        assertThat(html).contains(message).doesNotContain("id=\"" + recoveryId + "\"", ">이전</a>");
    }

    @Test
    void recoveryHrefEncodesSearchDataAndKeepsAClosedApplicationPath() throws Exception {
        String search = "absent & <tag>";
        String html = httpGet("/admin/users?page=2&status=REJECTED&search=" + encode(search)).body();
        assertThat(html).doesNotContain("<tag>").contains("&lt;tag&gt;");
        assertRecoveryLink(html, "users-first-page", "/admin/users", Map.of("page", "0", "status", "REJECTED", "search", search));
        assertThat(followRecovery(html, "users-first-page")).contains("조회 조건에 맞는 사용자가 없습니다");
    }

    private MockHttpServletRequestBuilder adminGet(String path) {
        return get(CONTEXT + path).contextPath(CONTEXT).with(user(details.loadUserByUsername(ADMIN)));
    }

    private MockHttpServletRequestBuilder adminPost(String path) {
        return post(CONTEXT + path).contextPath(CONTEXT).with(user(details.loadUserByUsername(ADMIN))).with(csrf());
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        var response = browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + CONTEXT + path); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static URI recoveryUri(String html, String id) {
        var link = Pattern.compile("id=\"" + Pattern.quote(id) + "\" href=\"([^\"]+)\"").matcher(html);
        assertThat(link.find()).isTrue();
        return URI.create(link.group(1).replace("&amp;", "&"));
    }

    private static void assertRecoveryLink(String html, String id, String expectedPath, Map<String, String> expectedQuery) {
        URI link = recoveryUri(html, id);
        assertThat(link.isAbsolute()).isFalse();
        assertThat(link.getRawAuthority()).isNull();
        assertThat(link.getPath()).isEqualTo(CONTEXT + expectedPath);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String field : link.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            actual.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertThat(actual).isEqualTo(expectedQuery);
    }

    private String followRecovery(String html, String id) throws Exception {
        URI link = recoveryUri(html, id);
        assertThat(link.getPath()).startsWith(CONTEXT + "/admin/");
        return httpGet(link.toString().substring(CONTEXT.length())).body();
    }
}
