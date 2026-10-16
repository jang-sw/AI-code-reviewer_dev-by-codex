package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real security filters, store, Tomcat and Jasper; only this class's dedicated H2 is changed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_throttle_recovery_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.enabled=false", "app.review.worker-enabled=false",
        "server.address=127.0.0.1", "server.servlet.context-path=/auth-throttle-fixture",
        "server.servlet.session.cookie.secure=false", "app.security.login-account-attempts=1",
        "app.security.login-address-attempts=1", "app.security.login-window-seconds=60",
        "app.security.signup-address-attempts=1", "app.security.signup-window-seconds=60"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthThrottleRecoveryHttpTest {
    private static final String CONTEXT = "/auth-throttle-fixture";
    private static final String MEMBER = "blockedmember";
    private static final String APPLICANT = "blockedapplicant";
    private static final String GIT = "blocked-private-git";
    private static final String PASSWORD = "Synthetic-auth-fixture-password-6952!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String RETURN = "https://private-return.invalid/collect";
    private static final String FORGED = "private-forged-error-message";
    private static final String FAULT_BUCKET = "unavailable_auth_recovery_fixture_bucket";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    private final List<HttpClient> browsers = new ArrayList<>();

    @BeforeEach
    void ownedAccountsAndIndependentQuotaOnly() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM auth_attempt_bucket");
        jdbc.update("UPDATE auth_attempt_policy SET policy_fingerprint=NULL");
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES(?,?,?,'USER')",
                MEMBER, HASH, "fixture-member-git");
    }

    @AfterEach
    void noExternalCallsAndCloseOwnedClients() {
        browsers.forEach(HttpClient::close);
        verifyNoInteractions(git, ai);
    }

    @ParameterizedTest
    @CsvSource({"/login,429", "/signup,429", "/login,503", "/signup,503"})
    void rejectedAuthenticationRendersSafeHtmlAndReturnsToAnEmptyFormWithoutMutation(String path, int status) throws Exception {
        var browser = browser();
        if (status == 429) consumeOneRealAttempt(browser, path);
        String token = csrf(get(browser, path).body());
        var before = snapshot(false);
        boolean unavailable = status == 503;
        if (unavailable) jdbc.execute("ALTER TABLE auth_attempt_bucket RENAME TO " + FAULT_BUCKET);
        try {
            var response = post(browser, path, submitted(path, token));
            assertDenial(response, path, status, token);
            assertThat(snapshot(unavailable)).isEqualTo(before);
            String recovered = followPrimary(browser, response.body(), path);
            assertEmptyForm(recovered, path);
            assertThat(snapshot(unavailable)).isEqualTo(before);
            assertRedirect(get(browser, "/projects"), "/login");
            if (status == 429) {
                // Opening the offered GET form does not clear the exhausted fixed window.
                var stillLimited = post(browser, path, submitted(path, csrf(recovered)));
                assertDenial(stillLimited, path, 429, csrf(recovered));
                assertThat(snapshot(false)).isEqualTo(before);
            }
        } finally {
            if (unavailable) jdbc.execute("ALTER TABLE " + FAULT_BUCKET + " RENAME TO auth_attempt_bucket");
        }
        assertThat(snapshot(false)).isEqualTo(before);
        if (unavailable) {
            // Once the owned table is restored, a fresh request reaches the normal authentication/signup flow.
            consumeOneRealAttempt(browser, path);
            String scope = path.equals("/login") ? "LOGIN" : "SIGNUP";
            assertThat(jdbc.queryForList("SELECT attempt_count FROM auth_attempt_bucket WHERE scope=?", Integer.class, scope))
                    .hasSize(2).containsOnly(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/signup"})
    void invalidCsrfStillWinsOverUnavailableQuotaStorageAndDoesNotProcessCredentials(String path) throws Exception {
        var browser = browser();
        assertThat(get(browser, path).statusCode()).isEqualTo(200);
        var before = snapshot(false);
        jdbc.execute("ALTER TABLE auth_attempt_bucket RENAME TO " + FAULT_BUCKET);
        try {
            var response = post(browser, path, submitted(path, "private-invalid-csrf-fixture"));
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("Retry-After")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(response.body()).contains("양식을 다시 열어 주세요", "제출 내용을 처리하지 않았습니다")
                    .doesNotContain("HTTP 503", "private-invalid-csrf-fixture", MEMBER, APPLICANT, GIT, PASSWORD, HASH, RETURN, FORGED);
            assertEmptyForm(followPrimary(browser, response.body(), path), path);
            assertThat(snapshot(true)).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE " + FAULT_BUCKET + " RENAME TO auth_attempt_bucket");
        }
        assertThat(snapshot(false)).isEqualTo(before);
        assertRedirect(get(browser, "/projects"), "/login");
    }

    private void consumeOneRealAttempt(HttpClient browser, String path) throws Exception {
        String token = csrf(get(browser, path).body());
        if (path.equals("/login")) {
            var response = post(browser, path, Map.of("_csrf", token, "username", MEMBER,
                    "password", "Synthetic-wrong-first-password!"));
            assertRedirect(response, "/login?error");
        } else {
            var response = post(browser, path, Map.of("_csrf", token, "username", "firstfixtureapplicant",
                    "gitUsername", "first-fixture-git", "password", PASSWORD, "confirmPassword", PASSWORD));
            assertRedirect(response, "/signup?submitted");
            assertThat(jdbc.queryForMap("SELECT role,enabled,approval_status FROM app_user WHERE username='firstfixtureapplicant'"))
                    .containsEntry("role", "USER").containsEntry("enabled", false).containsEntry("approval_status", "PENDING");
        }
    }

    private Map<String, String> submitted(String path, String token) {
        return Map.of("_csrf", token, "username", path.equals("/login") ? MEMBER : APPLICANT,
                "password", PASSWORD, "confirmPassword", PASSWORD, "gitUsername", GIT,
                "returnUrl", RETURN, "errorMessage", FORGED, "recoveryPath", RETURN);
    }

    private void assertDenial(HttpResponse<String> response, String path, int status, String token) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        String retry = response.headers().firstValue("Retry-After").orElseThrow();
        if (status == 503) assertThat(retry).isEqualTo("30");
        else assertThat(Long.parseLong(retry)).isBetween(1L, 60L);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .containsIgnoringCase("text/html").containsIgnoringCase("charset=UTF-8");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'self'", "form-action 'self'", "frame-ancestors 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options").orElseThrow()).isEqualTo("DENY");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        String title = status == 429
                ? path.equals("/login") ? "로그인 시도가 잠시 제한되었습니다" : "가입 요청이 잠시 제한되었습니다"
                : path.equals("/login") ? "지금은 로그인을 처리할 수 없습니다" : "지금은 가입 요청을 처리할 수 없습니다";
        assertThat(response.body()).contains("<!doctype html>", "HTTP " + status, title,
                path.equals("/login") ? "로그인 양식 다시 열기" : "회원가입 양식 다시 열기")
                .doesNotContain(MEMBER, APPLICANT, GIT, PASSWORD, HASH, RETURN, FORGED, token,
                        "auth_attempt_", "SQLException", "SELECT ", "INSERT ", "jdbc:", "127.0.0.1", "<form", "type=\"password\"",
                        "가입 요청을 확인했습니다", "review-worker-paused");
        if (status == 429) assertThat(response.body()).contains(retry + "초 후", "양식을 다시 열어도 제한은 초기화되지 않으며");
        else assertThat(response.body()).contains("요청을 처리하지 않았습니다.", "잠시 후 다시 시도해 주세요.");
    }

    private String followPrimary(HttpClient browser, String html, String path) throws Exception {
        var mains = Pattern.compile("<main\\b[^>]*\\bid=\"main\"[^>]*>(.*?)</main>", Pattern.DOTALL)
                .matcher(html).results().map(match -> match.group(1)).toList();
        assertThat(mains).hasSize(1);
        var primary = Pattern.compile("<a\\b[^>]*class=\"button\"[^>]*href=\"([^\"]+)\"")
                .matcher(mains.getFirst()).results().map(match -> match.group(1)).toList();
        assertThat(primary).hasSize(1);
        String href = primary.getFirst();
        assertThat(href).isEqualTo(CONTEXT + path);
        var response = browser.send(HttpRequest.newBuilder(uri("/").resolve(href)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        return response.body();
    }

    private void assertEmptyForm(String html, String path) {
        assertThat(html).doesNotContain(MEMBER, APPLICANT, GIT, PASSWORD, HASH, RETURN, FORGED, "HTTP 429", "HTTP 503");
        for (String id : path.equals("/login") ? List.of("username", "password")
                : List.of("username", "gitUsername", "password", "confirmPassword")) {
            var field = Pattern.compile("<input\\b[^>]*\\bid=\"" + id + "\"[^>]*>").matcher(html);
            assertThat(field.find()).as(id).isTrue();
            var value = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(field.group());
            if (value.find()) assertThat(value.group(1)).as(id).isEmpty();
        }
        assertThat(csrf(html)).isNotBlank();
    }

    private Map<String, List<Map<String, Object>>> snapshot(boolean unavailable) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("app_user", "project", "review_run", "review_request", "reviewed_commit", "review_issue",
                "manual_review_file", "audit_event", "git_author_mapping")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY "
                    + (table.equals("review_request") ? "project_id" : "id")));
        }
        result.put("auth_attempt_policy", jdbc.queryForList("SELECT * FROM auth_attempt_policy ORDER BY scope"));
        result.put("auth_attempt_bucket", jdbc.queryForList("SELECT * FROM "
                + (unavailable ? FAULT_BUCKET : "auth_attempt_bucket") + " ORDER BY scope,key_hash"));
        return result;
    }

    private HttpClient browser() {
        var browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        browsers.add(browser);
        return browser;
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient browser, String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertRedirect(HttpResponse<?> response, String path) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(uri("/").resolve(response.headers().firstValue("Location").orElseThrow())).isEqualTo(uri(path));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + CONTEXT + path); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String csrf(String html) {
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        return token.group(1);
    }
}
