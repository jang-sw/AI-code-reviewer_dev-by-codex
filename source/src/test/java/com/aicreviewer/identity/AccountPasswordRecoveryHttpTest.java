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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real Tomcat/Jasper and separate browser cookies; only this class's dedicated H2 database is used. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:account_password_recovery_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.review.enabled=false", "app.review.worker-enabled=false",
        "server.address=127.0.0.1", "server.servlet.context-path=/password-recovery-fixture",
        "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountPasswordRecoveryHttpTest {
    private static final String CONTEXT = "/password-recovery-fixture";
    private static final String ADMIN = "recoveryadmin";
    private static final String MEMBER = "fixturemember";
    private static final String PASSWORD = "Synthetic-current-password-7286!";
    private static final String REPLACEMENT = "Synthetic-replacement-password-6403!";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String RULE = "비밀번호는 12자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요.";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean GitRepositoryClient git;
    @MockitoBean AiReviewClient ai;
    private final List<HttpClient> browsers = new ArrayList<>();
    private long adminId;
    private long memberId;

    @BeforeEach
    void syntheticAccountsOnly() {
        jdbc.update("DELETE FROM git_author_mapping");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM auth_attempt_bucket");
        adminId = add(ADMIN, "ADMIN");
        memberId = add(MEMBER, "USER");
    }

    @AfterEach
    void closeOwnedBrowsers() {
        browsers.forEach(HttpClient::close);
        verifyNoInteractions(git, ai);
    }

    static Stream<Arguments> invalidSelfPasswords() {
        return Stream.of(
                Arguments.of("private-wrong-current-fixture", REPLACEMENT, REPLACEMENT,
                        "currentPassword", "현재 비밀번호가 일치하지 않습니다."),
                Arguments.of(PASSWORD, "short-key", "short-key", "newPassword", RULE),
                Arguments.of(PASSWORD, "한".repeat(25), "한".repeat(25), "newPassword", RULE),
                Arguments.of(PASSWORD, PASSWORD, PASSWORD, "newPassword", "현재와 다른 새 비밀번호를 입력해 주세요."),
                Arguments.of(PASSWORD, REPLACEMENT, "private-confirm-mismatch-fixture",
                        "confirmPassword", "새 비밀번호 확인이 일치하지 않습니다."));
    }

    @ParameterizedTest
    @MethodSource("invalidSelfPasswords")
    void invalidSelfPasswordRendersLinkedBlankFormAndPreservesAccountAndAudit(
            String current, String replacement, String confirmation, String field, String message) throws Exception {
        var browser = login(MEMBER, PASSWORD);
        var before = account(memberId);
        var auditBefore = audits();
        String token = csrf(get(browser, "/account/password").body());
        var response = post(browser, "/account/password", Map.of("_csrf", token, "currentPassword", current,
                "newPassword", replacement, "confirmPassword", confirmation));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("role=\"alert\"", message, "href=\"#" + field + "\"", "비밀번호는 변경되지 않았습니다.")
                .doesNotContain(current, replacement, confirmation, HASH);
        assertThat(input(response.body(), field)).contains("aria-invalid=\"true\"", "password-error");
        assertBlankPasswords(response.body(), 3);
        assertThat(account(memberId)).isEqualTo(before);
        assertThat(audits()).isEqualTo(auditBefore);
        String nextPage = get(browser, "/account/password").body();
        assertThat(nextPage).doesNotContain("id=\"password-error\"", current, replacement, confirmation);
        assertBlankPasswords(nextPage, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10000})
    void resetRuleErrorShowsSeparateTargetWithoutExpandingTheFilteredPage(int page) throws Exception {
        for (int i = 0; i < 51; i++) add(MEMBER + String.format(java.util.Locale.ROOT, "%03d", i), "USER");
        var browser = login(ADMIN, PASSWORD);
        String listing = get(browser, "/admin/users?status=APPROVED&search=" + MEMBER + "&page=" + page).body();
        var before = account(memberId);
        var auditBefore = audits();
        String invalid = "한".repeat(25);
        var response = post(browser, resetPath(memberId), Map.of("_csrf", csrf(listing), "newPassword", invalid,
                "status", "APPROVED", "search", MEMBER, "page", Integer.toString(page)));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("비밀번호 초기화 다시 입력", RULE, "대상 아이디: <strong>" + MEMBER + "</strong>",
                "href=\"#password-reset-retry-value\"", "name=\"status\" value=\"APPROVED\"",
                "name=\"search\" value=\"" + MEMBER + "\"", "name=\"page\" value=\"" + page + "\"",
                "UTF-8 기준 72바이트 이하", CONTEXT + resetPath(memberId)).doesNotContain(invalid, PASSWORD, HASH);
        assertThat(input(response.body(), "password-reset-retry-value"))
                .contains("aria-invalid=\"true\"", "aria-describedby=\"password-reset-error password-reset-retry-help\"")
                .doesNotContain("value=");
        if (page == 0) {
            String rows = response.body().split("<tbody>", 2)[1].split("</tbody>", 2)[0];
            assertThat(Pattern.compile("<tr>").matcher(rows).results().count()).isEqualTo(50);
            assertThat(rows).doesNotContain("<strong>" + MEMBER + "</strong>");
            assertBlankPasswords(response.body(), 51);
        } else {
            assertThat(response.body()).contains("이 페이지에 표시할 사용자가 없습니다");
            assertBlankPasswords(response.body(), 1);
        }
        assertThat(account(memberId)).isEqualTo(before);
        assertThat(audits()).isEqualTo(auditBefore);
        assertThat(get(browser, "/admin/users?status=APPROVED").body()).doesNotContain("password-reset-retry-value");
    }

    @Test
    void roleCsrfAndReturnValidationBlockRecoveryBeforeDisclosingTheTarget() throws Exception {
        var member = login(MEMBER, PASSWORD);
        var administrator = login(ADMIN, PASSWORD);
        var anonymous = browser();
        var before = account(memberId);
        var auditBefore = audits();
        String memberToken = csrf(get(member, "/account/password").body());
        var forbidden = post(member, resetPath(memberId), Map.of("_csrf", memberToken, "newPassword", "short-key"));
        assertThat(forbidden.statusCode()).isEqualTo(403);
        String anonymousToken = csrf(get(anonymous, "/login").body());
        var unauthenticated = post(anonymous, resetPath(memberId), Map.of("_csrf", anonymousToken, "newPassword", "short-key"));
        assertRedirect(unauthenticated, "/login");
        var csrfFailure = post(administrator, resetPath(memberId), Map.of("_csrf", "private-invalid-csrf", "newPassword", "short-key"));
        assertThat(csrfFailure.statusCode()).isEqualTo(403);
        var invalidReturn = post(administrator, resetPath(memberId), Map.of("_csrf", csrf(get(administrator, "/admin/users").body()),
                "newPassword", "short-key", "page", "10001"));
        assertThat(invalidReturn.statusCode()).isEqualTo(400);
        for (var response : List.of(forbidden, unauthenticated, csrfFailure, invalidReturn)) {
            assertThat(response.body()).doesNotContain("password-reset-retry-value", "short-key", "private-invalid-csrf");
        }
        assertThat(account(memberId)).isEqualTo(before);
        assertThat(audits()).isEqualTo(auditBefore);
    }

    @Test
    void missingResetTargetRemains404EvenWhenItsPasswordIsInvalid() throws Exception {
        var administrator = login(ADMIN, PASSWORD);
        var accountsBefore = jdbc.queryForList("SELECT * FROM app_user ORDER BY id");
        var auditBefore = audits();
        var response = post(administrator, resetPath(Long.MAX_VALUE), Map.of("_csrf", csrf(get(administrator, "/admin/users").body()),
                "newPassword", "short-key"));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("password-reset-retry-value", "short-key");
        assertThat(jdbc.queryForList("SELECT * FROM app_user ORDER BY id")).isEqualTo(accountsBefore);
        assertThat(audits()).isEqualTo(auditBefore);
    }

    @Test
    void unapprovedAccountKeepsTheExistingRedirectAndCannotGetAResetRetryForm() throws Exception {
        jdbc.update("UPDATE app_user SET enabled=FALSE,approval_status='PENDING' WHERE id=?", memberId);
        var administrator = login(ADMIN, PASSWORD);
        var before = account(memberId);
        var auditBefore = audits();
        for (String replacement : List.of(REPLACEMENT, "short-key")) {
            var response = post(administrator, resetPath(memberId), Map.of("_csrf", csrf(get(administrator, "/admin/users").body()),
                    "newPassword", replacement));
            assertRedirect(response, "/admin/users");
            assertThat(get(administrator, "/admin/users").body()).doesNotContain("password-reset-retry-value", replacement);
        }
        assertThat(account(memberId)).isEqualTo(before);
        assertThat(audits()).isEqualTo(auditBefore);
    }

    @Test
    void freshSelfFormAfterErrorChangesOnceLogsOutAndRevokesAnotherBrowser() throws Exception {
        var first = login(MEMBER, PASSWORD);
        var other = login(MEMBER, PASSWORD);
        var before = account(memberId);
        String form = get(first, "/account/password").body();
        var invalid = post(first, "/account/password", Map.of("_csrf", csrf(form), "currentPassword", PASSWORD,
                "newPassword", REPLACEMENT, "confirmPassword", "private-mismatch"));
        assertThat(invalid.statusCode()).isEqualTo(400);
        var changed = post(first, "/account/password", Map.of("_csrf", csrf(get(first, "/account/password").body()),
                "currentPassword", PASSWORD, "newPassword", REPLACEMENT, "confirmPassword", REPLACEMENT));
        assertRedirect(changed, "/login?changed");
        assertOnlyPasswordChanged(before, memberId, "PASSWORD_CHANGED");
        assertRedirect(get(first, "/account/password"), "/login");
        var revoked = get(other, "/account/password");
        assertRedirect(revoked, "/login?expired");
        assertLoginResult(MEMBER, PASSWORD, "/login?error");
        assertLoginResult(MEMBER, REPLACEMENT, "/");
    }

    @Test
    void validAdministratorRetryResetsOnceAndRevokesEveryOldMemberBrowser() throws Exception {
        var administrator = login(ADMIN, PASSWORD);
        var first = login(MEMBER, PASSWORD);
        var other = login(MEMBER, PASSWORD);
        var before = account(memberId);
        var invalid = post(administrator, resetPath(memberId), Map.of("_csrf", csrf(get(administrator, "/admin/users").body()),
                "newPassword", "한".repeat(25), "status", "APPROVED", "search", MEMBER, "page", "2"));
        assertThat(invalid.statusCode()).isEqualTo(400);
        var changed = post(administrator, resetPath(memberId), Map.of("_csrf", csrf(invalid.body()), "newPassword", REPLACEMENT,
                "status", "APPROVED", "search", MEMBER, "page", "2"));
        assertRedirect(changed, "/admin/users?status=APPROVED&search=" + MEMBER + "&page=2");
        assertOnlyPasswordChanged(before, memberId, "USER_PASSWORD_RESET");
        for (var victim : List.of(first, other)) {
            assertRedirect(get(victim, "/account/password"), "/login?expired");
        }
        assertThat(get(administrator, "/admin/users").statusCode()).isEqualTo(200);
        assertLoginResult(MEMBER, PASSWORD, "/login?error");
        assertLoginResult(MEMBER, REPLACEMENT, "/");
    }

    @Test
    void lastAdministratorErrorKeepsItsExistingRedirectWithoutChangingCredentialsOrAudit() throws Exception {
        var administrator = login(ADMIN, PASSWORD);
        var before = account(adminId);
        var auditBefore = audits();
        var response = post(administrator, "/admin/users/" + adminId + "/enabled", Map.of(
                "_csrf", csrf(get(administrator, "/admin/users").body()), "enabled", "false"));
        assertRedirect(response, "/admin/users");
        String listing = get(administrator, "/admin/users").body();
        assertThat(listing).contains("마지막 활성 관리자는 비활성화할 수 없습니다.")
                .doesNotContain("password-reset-retry-value");
        assertThat(account(adminId)).isEqualTo(before);
        assertThat(audits()).isEqualTo(auditBefore);
    }

    private void assertOnlyPasswordChanged(Map<String, Object> before, long id, String action) {
        var expected = new LinkedHashMap<>(before);
        var after = account(id);
        assertThat(passwords.matches(REPLACEMENT, (String) after.get("password_hash"))).isTrue();
        assertThat(passwords.matches(PASSWORD, (String) after.get("password_hash"))).isFalse();
        expected.put("password_hash", after.get("password_hash"));
        expected.put("security_version", ((Number) before.get("security_version")).longValue() + 1);
        assertThat(after).isEqualTo(expected);
        assertThat(audits()).hasSize(1);
        assertThat(audits().getFirst()).containsEntry("action", action).containsEntry("target_type", "USER").containsEntry("target_id", id);
        assertThat(audits().toString()).doesNotContain(PASSWORD, REPLACEMENT, HASH, (String) after.get("password_hash"));
    }

    private long add(String username, String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) VALUES(?,?,?,?)", username, HASH, username, role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private Map<String, Object> account(long id) { return jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", id); }
    private List<Map<String, Object>> audits() { return jdbc.queryForList("SELECT * FROM audit_event ORDER BY id"); }
    private static String resetPath(long id) { return "/admin/users/" + id + "/password"; }

    private HttpClient browser() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        browsers.add(client);
        return client;
    }

    private HttpClient login(String username, String password) throws Exception {
        var client = browser();
        assertRedirect(post(client, "/login", Map.of("_csrf", csrf(get(client, "/login").body()), "username", username, "password", password)), "/");
        return client;
    }

    private void assertLoginResult(String username, String password, String target) throws Exception {
        var client = browser();
        var response = post(client, "/login", Map.of("_csrf", csrf(get(client, "/login").body()), "username", username, "password", password));
        assertRedirect(response, target);
    }

    private void assertRedirect(HttpResponse<?> response, String path) {
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        // Servlet redirects may be absolute or root-relative; the complete destination must stay exact.
        assertThat(uri("/").resolve(location)).isEqualTo(uri(path));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + CONTEXT + path); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String csrf(String html) {
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    private static String input(String html, String id) {
        var input = Pattern.compile("<input\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
        assertThat(input.find()).isTrue();
        return input.group();
    }

    private static void assertBlankPasswords(String html, long expected) {
        var inputs = Pattern.compile("<input\\b[^>]*\\btype=\"password\"[^>]*>").matcher(html).results().map(result -> result.group()).toList();
        assertThat(inputs).hasSize((int) expected).allSatisfy(input -> assertThat(input).doesNotContain("value="));
    }
}
