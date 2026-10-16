package com.aicreviewer.web;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.identity.AccountUserDetailsService;
import com.aicreviewer.operations.OperationsTelemetryService;
import com.aicreviewer.review.ProjectReviewLock;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
    @MockitoBean OperationsTelemetryService telemetry;
    // H2 cannot acquire PostgreSQL advisory leases; controllers, transactions and SQL remain real.
    @MockitoBean ProjectReviewLock locks;
    private long administratorId;
    private HttpClient browser;

    @BeforeEach
    void syntheticDataOnly() throws Exception {
        // This dedicated H2 URL cannot use TEST_DATABASE_URL or any existing PostgreSQL database.
        jdbc.update("DELETE FROM review_request");
        jdbc.update("DELETE FROM review_run");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM git_author_mapping");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user WHERE username <> ?", ADMIN);
        jdbc.update("DELETE FROM auth_attempt_bucket");
        administratorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, ADMIN);
        when(telemetry.observation()).thenReturn(monitoringObservation("STARTING", false));
        when(locks.tryAcquire(anyLong())).thenReturn(Optional.of(() -> { }));
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

    @Test
    void auditRendersFiftyNewestEventsThenOneWithEscapedSystemDetailAndLinkedKeyboardRegion() throws Exception {
        String longDetail = "긴 감사 내용 ".repeat(90);
        String untrustedDetail = "<script>alert(1)</script>&" + longDetail;
        for (int i = 0; i < 51; i++) {
            jdbc.update("INSERT INTO audit_event(actor_id,action,target_type,target_id,detail,created_at) VALUES(?,?,'USER',?,?,?)",
                    i == 50 ? null : administratorId, auditAction(i), administratorId,
                    i == 50 ? untrustedDetail : "Synthetic audit detail " + i,
                    java.sql.Timestamp.valueOf(i == 0 ? "2099-01-01 00:00:00" : "2000-01-01 00:00:00"));
        }
        var before = domainState();
        String first = httpGet("/admin/audit").body();
        assertThat(auditActions(first)).containsExactlyElementsOf(IntStream.iterate(50, i -> i >= 1, i -> i - 1)
                .mapToObj(AdminEmptyPageHttpTest::auditAction).toList());
        assertThat(tableRows(first)).hasSize(50);
        assertThat(tableRows(first).getFirst()).contains("시스템", auditAction(50), "&lt;script&gt;alert(1)&lt;/script&gt;&amp;" + longDetail)
                .doesNotContain("<script>alert(1)</script>");
        assertThat(first).contains("id=\"audit-scroll-hint\"", "키보드는 표를 선택한 뒤 좌우 방향키를 사용하세요.")
                .doesNotContain(untrustedDetail, ">이전</a>");
        var region = Pattern.compile("<div\\b[^>]*aria-label=\"감사 기록 표, 좌우 스크롤\"[^>]*>").matcher(first);
        assertThat(region.find()).isTrue();
        assertThat(region.group()).contains("tabindex=\"0\"", "role=\"region\"", "aria-describedby=\"audit-scroll-hint\"");

        var next = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>다음</a>").matcher(first);
        assertThat(next.find()).isTrue();
        URI nextPage = URI.create(next.group(1).replace("&amp;", "&"));
        assertApplicationLink(nextPage, "/admin/audit", Map.of("page", "1"));
        String second = httpGet(nextPage.toString().substring(CONTEXT.length())).body();
        assertThat(auditActions(second)).containsExactly(auditAction(0));
        assertThat(tableRows(second)).hasSize(1);
        assertThat(second).contains(ADMIN, ">이전</a>").doesNotContain(">다음</a>");
        assertThat(domainState()).isEqualTo(before);

        jdbc.update("DELETE FROM audit_event WHERE action LIKE 'OBSERVATION_FIXTURE_%'");
        var emptyBefore = domainState();
        String empty = httpGet("/admin/audit").body();
        assertThat(empty).contains("아직 기록이 없습니다").doesNotContain("audit-scroll-hint", "감사 기록 표, 좌우 스크롤", "audit-first-page");
        assertThat(domainState()).isEqualTo(emptyBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ATTENTION", "FAILED", "STALE", "NEVER_RUN", "QUEUED", "REQUEST_DELAYED", "RATE_LIMITED"
    })
    void operationsDeepEmptyPageReturnsToTheSameFilterWithoutChangingTheObservation(String filter) throws Exception {
        long projectId = syntheticOperation(filter);
        var before = domainState();
        String deep = httpGet("/admin/operations?filter=" + filter + "&page=10000").body();
        assertThat(deep).contains(">이전</a>").doesNotContain(">다음</a>", "operations-scroll-hint");
        assertRecoveryLink(deep, "operations-first-page", "/admin/operations", Map.of("page", "0", "filter", filter));
        String first = followRecovery(deep, "operations-first-page");
        assertThat(first).contains("Synthetic operations " + filter, "id=\"operations-scroll-hint\"", "aria-describedby=\"operations-scroll-hint\"",
                        "운영 현황 표, 좌우 스크롤", CONTEXT + "/projects/" + projectId)
                .doesNotContain("id=\"operations-first-page\"", ">이전</a>", ">다음</a>", "private-operation-error");
        before.get("review_request").forEach(request -> assertThat(first).doesNotContain(request.get("request_id").toString()));
        assertThat(first).containsPattern("<option value=\"" + filter + "\"\\s+selected>");
        assertThat(tableRows(first)).hasSize(1);
        assertThat(domainState()).isEqualTo(before);

        jdbc.update("DELETE FROM review_request WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM review_run WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM project WHERE id=?", projectId);
        var emptyBefore = domainState();
        String emptyFirst = httpGet("/admin/operations?filter=" + filter + "&page=0").body();
        assertThat(emptyFirst).contains("조회 조건에 해당하는 프로젝트가 없습니다")
                .doesNotContain("operations-first-page", "operations-scroll-hint", ">이전</a>", ">다음</a>");
        assertThat(emptyFirst).containsPattern("<option value=\"" + filter + "\"\\s+selected>");
        assertThat(domainState()).isEqualTo(emptyBefore);
    }

    @Test
    void observationQueryErrorsAndOrdinaryUserDenialsPreserveAllDomainRows() throws Exception {
        syntheticOperation("FAILED");
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role) SELECT 'observationmember',password_hash,'observationmember','USER' FROM app_user WHERE id=?", administratorId);
        var before = domainState();
        for (String path : List.of("/admin/audit?page=-1", "/admin/audit?page=10001", "/admin/audit?page=not-a-number",
                "/admin/operations?filter=ALL", "/admin/operations?filter=FAILED&page=-1",
                "/admin/operations?filter=FAILED&page=10001", "/admin/operations?filter=FAILED&page=not-a-number")) {
            var response = browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).as(path).isEqualTo(400);
        }
        for (String path : List.of("/admin/audit", "/admin/operations?filter=RATE_LIMITED&page=10000",
                "/admin/operations?filter=ALL&page=-1", "/admin/monitoring", "/admin/monitoring/snapshot")) {
            mvc.perform(get(CONTEXT + path).contextPath(CONTEXT)
                            .with(user(details.loadUserByUsername("observationmember"))))
                    .andExpect(status().isForbidden());
        }
        assertThat(domainState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTING", "READY", "STALE", "FAILED"})
    void monitoringRendersAnHonestObservationStateWithoutChangingBusinessRows(String state) throws Exception {
        when(telemetry.observation()).thenReturn(monitoringObservation(state, false));
        var before = domainState();
        var response = httpGet("/admin/monitoring");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String html = response.body();
        assertThat(html).contains("data-observation-status=\"" + state + "\"", "role=\"status\"",
                "이 화면의 알림은 이메일이나 푸시로 발송되지 않습니다.",
                "화면을 새로고침해도 즉시 재집계하지 않습니다.",
                "지금 접속한 서버의 설정입니다.", "일시 중지 · 요청은 저장되며", "꺼짐 · 이미 접수된 요청과 직접 요청의 처리를 중지하는 설정은 아닙니다.");
        if (state.equals("READY")) {
            assertThat(html).contains("최신 관측 사용 가능", "id=\"monitoring-counts\"", "화면 알림",
                    "최근 실행이 실패한 프로젝트 3개", "오래된 미완료 요청 2건", "2026-10-15 12:00:00",
                    "2026-10-15 12:00:30", "30초 전 기준", "datetime=\"2026-10-15T12:00:00Z\"",
                    "처리 중이라는 기록은 실제 작업자가 실행 중임을 보장하지 않습니다.")
                    .doesNotContain("운영 수치 확인 불가", "첫 수집 대기", "관측이 오래됨", "수집 실패");
        } else {
            assertThat(html).contains("운영 수치 확인 불가", "정상 또는 0건으로 해석하지 마세요.", "프로젝트별 상태 직접 확인")
                    .doesNotContain("id=\"monitoring-counts\"", "id=\"monitoring-alerts-heading\"", "id=\"monitoring-scope-heading\"", "최근 실행이 실패한 프로젝트 3개",
                            "오래된 미완료 요청 2건", "이 관측에서 최근 실행 실패나 오래된 미완료 요청은 없습니다.",
                            "filter=FAILED", "filter=REQUEST_DELAYED", "filter=QUEUED");
            assertThat(html).contains(switch (state) {
                case "STARTING" -> "첫 수집 대기";
                case "STALE" -> "관측이 오래됨";
                default -> "수집 실패";
            });
            if (state.equals("STARTING")) {
                assertThat(html).contains("아직 성공한 관측 없음", "아직 시도 기록 없음")
                        .doesNotContain("2026-10-15 12:00:00", "초 전 기준");
            } else {
                assertThat(html).contains("2026-10-15 12:00:00", "2026-10-15 12:00:30", "180초 전 기준")
                        .doesNotContain("아직 성공한 관측 없음", "아직 시도 기록 없음");
            }
        }
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void readyMonitoringShowsObservedZerosWithoutClaimingThereAreNoOtherOperationalConcerns() throws Exception {
        when(telemetry.observation()).thenReturn(monitoringObservation("READY", true));
        var before = domainState();
        String html = httpGet("/admin/monitoring").body();
        assertThat(html).contains("최신 관측 사용 가능", "id=\"monitoring-counts\"", "미완료 요청 없음",
                        "이 관측에서 최근 실행 실패나 오래된 미완료 요청은 없습니다.",
                        "실패·지연이 없어도 미실행 등 다른 확인 대상은 프로젝트별 운영 상태에서 확인하세요.")
                .doesNotContain("운영 수치 확인 불가", "실패한 프로젝트 확인", "지연된 요청 확인");
        assertApplicationLink(monitoringLink(html, "실행 대기 요청"), "/admin/operations", Map.of("filter", "QUEUED"));
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void readyMonitoringAlertAndQueueLinksActuallyOpenTheCorrespondingOperationalFilters() throws Exception {
        syntheticOperation("FAILED");
        syntheticOperation("REQUEST_DELAYED");
        syntheticOperation("QUEUED");
        when(telemetry.observation()).thenReturn(monitoringObservation("READY", false));
        var before = domainState();
        String html = httpGet("/admin/monitoring").body();
        for (var expected : Map.of("실패한 프로젝트 확인", "FAILED", "지연된 요청 확인", "REQUEST_DELAYED", "실행 대기 요청", "QUEUED").entrySet()) {
            URI link = monitoringLink(html, expected.getKey());
            assertApplicationLink(link, "/admin/operations", Map.of("filter", expected.getValue()));
            String operations = httpGet(link.toString().substring(CONTEXT.length())).body();
            assertThat(operations).containsPattern("<option value=\"" + expected.getValue() + "\"\\s+selected>");
            assertThat(tableRows(operations)).hasSize(expected.getValue().equals("FAILED") ? 3 : 2);
        }
        assertThat(domainState()).isEqualTo(before);
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

    @Test
    void staleGitCorrectionExplainsCurrentValueOutsideTheListAndItsFreshRetryChangesOnlyTheIntendedAccount() throws Exception {
        long target = syntheticAccount("correction_target");
        syntheticAccount("other_admin");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE username='other_admin'");
        jdbc.update("UPDATE app_user SET enabled=FALSE,approval_status='REJECTED' WHERE id=?", target);
        String oldForm = httpGet("/admin/users?status=REJECTED&search=correction_target").body();
        String path = "/admin/users/" + target + "/git-username";
        mvc.perform(post(CONTEXT + path).contextPath(CONTEXT)
                        .with(user(details.loadUserByUsername("other_admin"))).with(csrf())
                        .param("expectedGitUsername", "correction_target").param("newGitUsername", "already-corrected"))
                .andExpect(status().is3xxRedirection());
        var before = domainState();
        var attempted = Map.of("_csrf", formValue(oldForm, "_csrf"), "expectedGitUsername", "correction_target",
                "newGitUsername", "intended-name", "status", "PENDING", "search", "different-account", "page", "3");

        var stale = httpPost(path, attempted);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.headers().firstValue("Location")).isEmpty();
        assertThat(stale.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(stale.body()).contains("대상 아이디: <strong>correction_target</strong>",
                        "현재 Git 사용자명: <strong>already-corrected</strong>", "다른 관리자가 Git 계정을 변경했습니다.",
                        "href=\"#git-correction-value\"", "이 페이지에 표시할 사용자가 없습니다")
                .doesNotContain("value=\"correction_target\"");
        String retry = formFor(stale.body(), path);
        assertThat(formValue(retry, "expectedGitUsername")).isEqualTo("already-corrected");
        assertThat(formValue(retry, "newGitUsername")).isEqualTo("intended-name");
        assertThat(formValue(retry, "status")).isEqualTo("PENDING");
        assertThat(formValue(retry, "search")).isEqualTo("different-account");
        assertThat(formValue(retry, "page")).isEqualTo("3");
        String field = elementOpeningTag(retry, "git-correction-value");
        assertThat(field).contains("aria-invalid=\"true\"");
        List<String> descriptions = List.of(attribute(field, "aria-describedby").split("\\s+"));
        assertThat(descriptions).hasSize(2).contains("git-correction-help");
        var errorIds = Pattern.compile("<p\\b([^>]*)>(.*?)</p>", Pattern.DOTALL).matcher(stale.body()).results()
                .filter(match -> match.group(1).contains("role=\"alert\"") && match.group(2).contains("href=\"#git-correction-value\""))
                .map(match -> attribute(match.group(1), "id")).toList();
        assertThat(errorIds).hasSize(1);
        assertThat(descriptions).contains(errorIds.getFirst());
        for (String description : descriptions) {
            assertThat(Pattern.compile("\\bid=\"" + Pattern.quote(description) + "\"").matcher(stale.body()).results().count()).isEqualTo(1);
        }
        assertThat(domainState()).isEqualTo(before);

        var saved = httpPost(path, Map.of("_csrf", formValue(retry, "_csrf"),
                "expectedGitUsername", formValue(retry, "expectedGitUsername"), "newGitUsername", formValue(retry, "newGitUsername"),
                "status", formValue(retry, "status"), "search", formValue(retry, "search"), "page", formValue(retry, "page")));
        assertThat(saved.statusCode()).isEqualTo(302);
        assertThat(uri("/").resolve(saved.headers().firstValue("Location").orElseThrow()))
                .isEqualTo(uri("/admin/users?status=PENDING&search=different-account&page=3"));
        var expectedAccount = new LinkedHashMap<>(before.get("app_user").stream().filter(row -> ((Number) row.get("id")).longValue() == target).findFirst().orElseThrow());
        expectedAccount.put("git_username", "intended-name");
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", target)).isEqualTo(expectedAccount);
        var after = domainState();
        assertThat(after.get("app_user").stream().filter(row -> ((Number) row.get("id")).longValue() != target).toList())
                .isEqualTo(before.get("app_user").stream().filter(row -> ((Number) row.get("id")).longValue() != target).toList());
        assertThat(after.get("audit_event")).hasSize(before.get("audit_event").size() + 1);
        assertThat(after.get("audit_event").getLast()).containsEntry("actor_id", administratorId)
                .containsEntry("action", "USER_GIT_USERNAME_CHANGED").containsEntry("target_id", target);
        for (String table : before.keySet()) {
            if (!List.of("app_user", "audit_event").contains(table)) assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        }
    }

    @Test
    void mappingErrorRestoresTheActualFiftyFirstMatchingUserWithoutExpandingOrDuplicatingTheSearchPage() throws Exception {
        for (int i = 0; i < 51; i++) syntheticAccount(String.format(java.util.Locale.ROOT, "mapping_%03d", i));
        long selected = jdbc.queryForObject("SELECT id FROM app_user WHERE username='mapping_050'", Long.class);
        String initial = httpGet("/admin/git-authors?userSearch=mapping_").body();
        var initiallyDisplayed = userOptions(initial);
        assertThat(initiallyDisplayed).hasSize(50).doesNotContainKey(selected);
        assertThat(initiallyDisplayed.values()).containsExactlyElementsOf(IntStream.range(0, 50)
                .mapToObj(i -> String.format(java.util.Locale.ROOT, "mapping_%03d", i)).toList());
        var before = domainState();

        var response = httpPost("/admin/git-authors", Map.of("_csrf", formValue(initial, "_csrf"),
                "userId", Long.toString(selected), "repositoryOrigin", "https://github.com", "authorEmail", "incorrect-email",
                "userSearch", "mapping_", "page", "2"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        String html = response.body();
        var restored = userOptions(html);
        assertThat(restored).hasSize(51).containsAllEntriesOf(initiallyDisplayed);
        assertThat(restored.get(selected)).isEqualTo("mapping_050 (직전 선택 · 현재 활성)");
        assertThat(Pattern.compile("<option\\b[^>]*value=\"" + selected + "\"[^>]*selected").matcher(html).results().count()).isEqualTo(1);
        assertThat(html).contains("활성 사용자 50명까지 표시합니다.", "href=\"#authorEmail\"", "직전 선택 · 현재 활성");
        String retry = formFor(html, "/admin/git-authors");
        assertThat(formValue(retry, "repositoryOrigin")).isEqualTo("https://github.com");
        assertThat(formValue(retry, "authorEmail")).isEqualTo("incorrect-email");
        assertThat(formValue(retry, "userSearch")).isEqualTo("mapping_");
        assertThat(formValue(retry, "page")).isEqualTo("2");
        assertThat(elementOpeningTag(retry, "authorEmail")).contains("aria-invalid=\"true\"", "mapping-email-error");
        assertThat(domainState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "PENDING", "REJECTED"})
    void mappingSubmissionAfterTheSelectedAccountBecomesInactiveAsksForANewEligibleSelection(String approval) throws Exception {
        long selected = syntheticAccount("stale_selection");
        String initial = httpGet("/admin/git-authors?userSearch=stale_selection").body();
        assertThat(userOptions(initial)).containsEntry(selected, "stale_selection");
        jdbc.update("UPDATE app_user SET enabled=FALSE,approval_status=? WHERE id=?", approval, selected);
        var before = domainState();

        var response = httpPost("/admin/git-authors", Map.of("_csrf", formValue(initial, "_csrf"),
                "userId", Long.toString(selected), "repositoryOrigin", "https://github.com", "authorEmail", "safe+fixture@example.invalid",
                "userSearch", "stale_selection", "page", "2"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertInactiveMappingRecovery(response.body(), selected, "safe+fixture@example.invalid");
        assertThat(response.body()).doesNotContain("id=\"mapping-email-error\"");
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void invalidMappingEmailStillExplainsWhyTheFormerlyEligibleSelectionWasCleared() throws Exception {
        long selected = syntheticAccount("stale_selection");
        String initial = httpGet("/admin/git-authors?userSearch=stale_selection").body();
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=?", selected);
        var before = domainState();

        var response = httpPost("/admin/git-authors", Map.of("_csrf", formValue(initial, "_csrf"),
                "userId", Long.toString(selected), "repositoryOrigin", "https://github.com", "authorEmail", "incorrect-email",
                "userSearch", "stale_selection", "page", "2"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertInactiveMappingRecovery(response.body(), selected, "incorrect-email");
        assertThat(response.body()).contains("href=\"#authorEmail\"", "id=\"mapping-email-error\"");
        assertThat(elementOpeningTag(response.body(), "authorEmail")).contains("aria-invalid=\"true\"", "mapping-email-error");
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void staleBranchCorrectionShowsTheCurrentSnapshotAndSafeAttemptWithUnconfirmedLinkedInputs() throws Exception {
        long project = syntheticPausedProject();
        String path = "/admin/projects/" + project + "/branch";
        String initial = httpGet("/projects/" + project).body();
        assertThat(initial).doesNotContain("href=\"#corrected-branch\"", "href=\"#branch-correction-reason\"");
        String oldForm = formFor(initial, path);
        jdbc.update("UPDATE project SET review_branch='release/current',last_reviewed_sha=? WHERE id=?", "b".repeat(40), project);
        var before = domainState();

        var response = httpPost(path, Map.of("_csrf", formValue(oldForm, "_csrf"),
                "expectedBranch", formValue(oldForm, "expectedBranch"), "expectedCursor", formValue(oldForm, "expectedCursor"),
                "reviewBranch", "release/attempt", "reason", "이전 양식의 안전한 정정 사유", "confirmed", "true"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("브랜치 또는 진행 기준이 변경되었습니다", "현재 브랜치: <strong>release/current</strong>",
                "기존 리뷰와 이슈는 그대로 유지", "정정 후에도 현재 승인·일시 중지 상태는 유지됩니다.");
        String retry = formFor(response.body(), path);
        assertThat(formValue(retry, "expectedBranch")).isEqualTo("release/current");
        assertThat(formValue(retry, "expectedCursor")).isEqualTo("b".repeat(40));
        assertThat(formValue(retry, "reviewBranch")).isEqualTo("release/attempt");
        assertThat(formValue(retry, "reason")).isEqualTo("이전 양식의 안전한 정정 사유");
        assertBranchErrorLinksAndFreshConfirmation(response.body(), retry);
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void branchFormSubmittedAfterTheProjectResumesShowsCurrentManagementWithoutLinksToHiddenInputs() throws Exception {
        long project = syntheticPausedProject();
        String path = "/admin/projects/" + project + "/branch";
        String oldForm = formFor(httpGet("/projects/" + project).body(), path);
        jdbc.update("UPDATE project SET status='APPROVED' WHERE id=?", project);
        var before = domainState();

        var response = httpPost(path, Map.of("_csrf", formValue(oldForm, "_csrf"),
                "expectedBranch", formValue(oldForm, "expectedBranch"), "expectedCursor", formValue(oldForm, "expectedCursor"),
                "reviewBranch", "release/attempt", "reason", "재개 전에 열었던 브랜치 정정 양식", "confirmed", "true"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("실행 전 승인 대기·반려 프로젝트 또는 리뷰가 일시 중지된 프로젝트만 브랜치를 정정할 수 있습니다.",
                        "리뷰 요청을 저장할 수 있어요", "리뷰 일시 중지")
                .doesNotContain("id=\"branch-correction\"", "id=\"corrected-branch\"", "id=\"branch-correction-reason\"",
                        "href=\"#corrected-branch\"", "href=\"#branch-correction-reason\"");
        assertThat(formFor(response.body(), "/admin/projects/" + project + "/pause")).contains("_csrf");
        assertThat(domainState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING"})
    void branchCorrectionWithAnActiveRequestExplainsTheCurrentRequestWithoutSavingTheAttempt(String state) throws Exception {
        long project = syntheticPausedProject();
        String path = "/admin/projects/" + project + "/branch";
        String oldForm = formFor(httpGet("/projects/" + project).body(), path);
        if (state.equals("RUNNING")) {
            jdbc.update("UPDATE review_run SET status='RUNNING',finished_at=NULL WHERE project_id=?", project);
            long run = jdbc.queryForObject("SELECT id FROM review_run WHERE project_id=?", Long.class, project);
            jdbc.update("INSERT INTO review_request(project_id,request_id,claim_token,state,source,requested_by,requested_at,available_at,last_attempt_at,attempt_count,run_id) "
                            + "VALUES(?,?,?,'RUNNING','MANUAL',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,?)",
                    project, UUID.randomUUID().toString(), UUID.randomUUID().toString(), administratorId, run);
        } else {
            jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at) "
                            + "VALUES(?,?,'QUEUED','MANUAL',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    project, UUID.randomUUID().toString(), administratorId);
        }
        var before = domainState();

        var response = httpPost(path, Map.of("_csrf", formValue(oldForm, "_csrf"),
                "expectedBranch", formValue(oldForm, "expectedBranch"), "expectedCursor", formValue(oldForm, "expectedCursor"),
                "reviewBranch", "release/attempt", "reason", "활성 요청이 끝난 뒤 확인할 사유", "confirmed", "true"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("아직 처리 중이거나 대기 중인 리뷰 요청이 남아 있습니다", "data-request-state=\"" + state + "\"",
                        "저장된 요청은 처리 재확인 시 프로젝트 상태에 따라 취소됩니다.")
                .doesNotContain("브랜치 또는 진행 기준이 변경되었습니다");
        String retry = formFor(response.body(), path);
        assertThat(formValue(retry, "expectedBranch")).isEqualTo("mian");
        assertThat(formValue(retry, "expectedCursor")).isEqualTo("a".repeat(40));
        assertThat(formValue(retry, "reviewBranch")).isEqualTo("release/attempt");
        assertThat(formValue(retry, "reason")).isEqualTo("활성 요청이 끝난 뒤 확인할 사유");
        assertBranchErrorLinksAndFreshConfirmation(response.body(), retry);
        assertThat(domainState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409})
    void expectedRecoveryErrorUsesOnlyOwnedGuidanceAndReturnsToTheExactProject(int expectedStatus) throws Exception {
        long project = syntheticPausedProject();
        String path = "/admin/projects/" + project + "/review-progress/reset";
        String initial = formFor(httpGet("/projects/" + project).body(), path);
        String repository = "https://github.com/fixture/recovery-http";
        String submittedRepository = expectedStatus == 400 ? repository + "-private-mismatch" : repository;
        String submittedCursor = expectedStatus == 409 ? "c".repeat(40) : "a".repeat(40);
        String privateReason = "private-recovery-reason-fixture";
        var before = domainState();

        var response = httpPost(path + "?returnUrl=" + encode("https://untrusted.example/private-return"),
                Map.of("_csrf", formValue(initial, "_csrf"), "repositoryUrl", submittedRepository,
                        "expectedCursor", submittedCursor, "reason", privateReason));
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).contains("리뷰 진행 기준을 복구하지 못했습니다", "role=\"alert\"", "복구 요청은 적용되지 않았습니다.",
                        expectedStatus == 400 ? "프로젝트 주소와 일치하지 않습니다" : "리뷰 진행 기준이 변경되었거나 이미 초기화되었습니다")
                .doesNotContain(submittedRepository, submittedCursor, privateReason, "untrusted.example", "private-return");
        URI link = fixedRecoveryLink(response.body(), "프로젝트로 돌아가기", "/projects/" + project);
        assertThat(domainState()).isEqualTo(before);
        assertThat(httpGet(link.toString().substring(CONTEXT.length())).body()).contains("리뷰가 일시 중지되었어요", path);
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void requestingReviewForAPausedProjectRendersTheRealCommonConflictAndFollowsItsFixedListLink() throws Exception {
        long project = syntheticPausedProject();
        String page = httpGet("/projects/" + project).body();
        var before = domainState();

        var response = httpPost("/projects/" + project + "/review?next=" + encode("https://untrusted.example/private-return"),
                Map.of("_csrf", formValue(page, "_csrf"), "reason", "private-common-conflict-input"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).contains("HTTP 409", "현재 상태에서는 처리할 수 없습니다", "항목의 상태가 바뀌었거나 다른 처리가 진행 중입니다.")
                .doesNotContain("승인된 프로젝트만 리뷰할 수 있습니다", "private-common-conflict-input", "untrusted.example", "private-return");
        URI link = fixedRecoveryLink(response.body(), "프로젝트 목록 열기", "/projects");
        assertThat(domainState()).isEqualTo(before);
        assertThat(httpGet(link.toString().substring(CONTEXT.length())).body()).contains("Recovery HTTP fixture");
        assertThat(domainState()).isEqualTo(before);
    }

    @Test
    void unexpectedRecoveryAuditWriteFailureRollsBackAndRendersOnlySafeCommonErrorWithAWorkingProjectListLink() throws Exception {
        long project = syntheticPausedProject();
        String path = "/admin/projects/" + project + "/review-progress/reset";
        String projectPage = httpGet("/projects/" + project).body();
        assertThat(projectPage).contains("id=\"review-worker-paused\"");
        String initial = formFor(projectPage, path);
        String privateReason = "private-audit-write-reason-fixture";
        String constraint = "recovery_http_audit_failure";
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT " + constraint
                + " CHECK(action <> 'PROJECT_REVIEW_PROGRESS_RESET' OR target_id <> " + project + ")");
        try {
            var before = domainState();
            var response = httpPost(path + "?next=" + encode("https://untrusted.example/private-return"),
                    Map.of("_csrf", formValue(initial, "_csrf"), "repositoryUrl", "https://github.com/fixture/recovery-http",
                            "expectedCursor", "a".repeat(40), "reason", privateReason));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(response.body()).contains("HTTP 500", "요청을 처리하지 못했습니다", "일시적인 문제로 요청을 완료하지 못했습니다.")
                    .doesNotContain("리뷰 진행 기준을 복구하지 못했습니다", privateReason, "untrusted.example", "private-return",
                            "https://github.com/fixture/recovery-http", "a".repeat(40), constraint, constraint.toUpperCase(java.util.Locale.ROOT),
                            "JdbcSQL", "DataIntegrityViolationException", "INSERT INTO audit_event", "PROJECT_REVIEW_PROGRESS_RESET",
                            "id=\"review-worker-paused\"");
            URI link = fixedRecoveryLink(response.body(), "프로젝트 목록 열기", "/projects");
            assertThat(domainState()).isEqualTo(before);
            assertThat(httpGet(link.toString().substring(CONTEXT.length())).body()).contains("Recovery HTTP fixture", "id=\"review-worker-paused\"");
            assertThat(domainState()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT " + constraint);
        }
    }

    private static void assertBranchErrorLinksAndFreshConfirmation(String html, String form) {
        var details = Pattern.compile("<details\\b[^>]*id=\"branch-correction\"[^>]*>").matcher(html);
        assertThat(details.find()).isTrue();
        assertThat(details.group()).contains("open");
        var error = Pattern.compile("<section\\b[^>]*id=\"branch-correction-error\"[^>]*>(.*?)</section>", Pattern.DOTALL).matcher(html);
        assertThat(error.find()).isTrue();
        assertThat(error.group()).contains("role=\"alert\"", "브랜치를 정정하지 않았습니다.");
        for (String id : List.of("corrected-branch", "branch-correction-reason")) {
            assertThat(Pattern.compile("href=\"#" + id + "\"").matcher(error.group()).results().count()).isEqualTo(1);
            for (String description : attribute(elementOpeningTag(form, id), "aria-describedby").split("\\s+")) {
                assertThat(Pattern.compile("\\bid=\"" + Pattern.quote(description) + "\"").matcher(html).results().count()).isEqualTo(1);
            }
            assertThat(attribute(elementOpeningTag(form, id), "aria-describedby")).contains("branch-correction-error");
        }
        var confirmation = Pattern.compile("<input\\b[^>]*name=\"confirmed\"[^>]*>").matcher(form);
        assertThat(confirmation.find()).isTrue();
        assertThat(confirmation.group()).contains("required").doesNotContain("checked");
    }

    private static URI fixedRecoveryLink(String html, String label, String path) {
        var links = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>" + Pattern.quote(label) + "</a>")
                .matcher(html).results().map(match -> URI.create(match.group(1).replace("&amp;", "&"))).toList();
        assertThat(links).as(label).hasSize(1);
        URI link = links.getFirst();
        assertThat(link.isAbsolute()).isFalse();
        assertThat(link.getRawAuthority()).isNull();
        assertThat(link.getPath()).isEqualTo(CONTEXT + path);
        assertThat(link.getRawQuery()).isNull();
        assertThat(link.getRawFragment()).isNull();
        return link;
    }

    private long syntheticPausedProject() {
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status,review_branch,last_reviewed_sha,approved_at) "
                        + "VALUES('Recovery HTTP fixture','https://github.com/fixture/recovery-http','GITHUB','github.com','fixture/recovery-http',?,'PAUSED','mian',?,TIMESTAMP '2000-01-01 00:00:00')",
                administratorId, "a".repeat(40));
        long project = jdbc.queryForObject("SELECT id FROM project WHERE repository_path='fixture/recovery-http'", Long.class);
        jdbc.update("INSERT INTO review_run(project_id,status,started_at,finished_at) VALUES(?,'FAILED',TIMESTAMP '2000-01-01 00:00:00',TIMESTAMP '2000-01-01 01:00:00')", project);
        return project;
    }

    private static void assertInactiveMappingRecovery(String html, long selected, String email) {
        assertThat(userOptions(html)).doesNotContainKey(selected);
        assertThat(html).contains("href=\"#userId\"", "id=\"mapping-user-error\"", "승인된 활성 사용자")
                .doesNotContain("직전 선택 · 현재 활성");
        String retry = formFor(html, "/admin/git-authors");
        assertThat(elementOpeningTag(retry, "userId")).contains("aria-invalid=\"true\"", "mapping-user-error");
        assertThat(formValue(retry, "repositoryOrigin")).isEqualTo("https://github.com");
        assertThat(formValue(retry, "authorEmail")).isEqualTo(email);
        assertThat(formValue(retry, "userSearch")).isEqualTo("stale_selection");
        assertThat(formValue(retry, "page")).isEqualTo("2");
    }

    private long syntheticAccount(String username) {
        jdbc.update("INSERT INTO app_user(username,password_hash,git_username,role,enabled,approval_status) VALUES(?,'unused-synthetic-hash',?,'USER',TRUE,'APPROVED')",
                username, username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private HttpResponse<String> httpPost(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return browser.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String formFor(String html, String path) {
        var forms = Pattern.compile("<form\\b([^>]*)>(.*?)</form>", Pattern.DOTALL).matcher(html).results()
                .filter(match -> match.group(1).contains("method=\"post\"")
                        && match.group(1).contains("action=\"" + CONTEXT + path + "\""))
                .map(java.util.regex.MatchResult::group).toList();
        assertThat(forms).hasSize(1);
        return forms.getFirst();
    }

    private static String formValue(String html, String name) {
        var values = Pattern.compile("<input\\b[^>]*>").matcher(html).results().map(java.util.regex.MatchResult::group)
                .filter(tag -> tag.contains("name=\"" + name + "\""))
                .map(tag -> attribute(tag, "value")).distinct().toList();
        assertThat(values).as(name).hasSize(1);
        return values.getFirst();
    }

    private static String elementOpeningTag(String html, String id) {
        var tags = Pattern.compile("<(?:input|select)\\b[^>]*>").matcher(html).results().map(java.util.regex.MatchResult::group)
                .filter(tag -> tag.contains("id=\"" + id + "\"")).toList();
        assertThat(tags).as(id).hasSize(1);
        return tags.getFirst();
    }

    private static String attribute(String tag, String name) {
        var value = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        assertThat(value.find()).as(name).isTrue();
        return value.group(1);
    }

    private static Map<Long, String> userOptions(String html) {
        var select = Pattern.compile("<select\\b[^>]*id=\"userId\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(html);
        assertThat(select.find()).isTrue();
        Map<Long, String> options = new LinkedHashMap<>();
        Pattern.compile("<option\\b[^>]*value=\"([0-9]+)\"[^>]*>([^<]+)</option>").matcher(select.group(1)).results().forEach(match -> {
            assertThat(options.put(Long.valueOf(match.group(1)), match.group(2))).as("duplicate selectable user").isNull();
        });
        return options;
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
        assertApplicationLink(link, expectedPath, expectedQuery);
    }

    private static void assertApplicationLink(URI link, String expectedPath, Map<String, String> expectedQuery) {
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

    private static String auditAction(int index) { return String.format(java.util.Locale.ROOT, "OBSERVATION_FIXTURE_%03d", index); }

    private static OperationsTelemetryService.Observation monitoringObservation(String state, boolean zero) {
        boolean starting = state.equals("STARTING");
        Instant observed = Instant.parse("2026-10-15T12:00:00Z");
        return new OperationsTelemetryService.Observation(state, starting ? null : observed,
                starting ? null : observed.plusSeconds(30), starting ? null : state.equals("READY") ? 30L : 180L, 120,
                Map.of("PENDING", 0L, "APPROVED", zero ? 0L : 3L, "REJECTED", 0L, "PAUSED", 0L),
                Map.of("QUEUED", zero ? 0L : 2L, "RUNNING", 0L, "SUCCEEDED", 0L, "FAILED", 0L, "CANCELLED", 0L),
                zero || starting ? null : observed.minusSeconds(7200), zero ? 0L : 2L, zero ? 0L : 3L);
    }

    private static URI monitoringLink(String html, String label) {
        var links = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html).results()
                .filter(match -> match.group(2).contains(label))
                .map(match -> URI.create(match.group(1).replace("&amp;", "&"))).toList();
        assertThat(links).as(label).hasSize(1);
        return links.getFirst();
    }

    private static List<String> auditActions(String html) {
        return Pattern.compile("<code>(OBSERVATION_FIXTURE_[0-9]{3})</code>").matcher(html).results()
                .map(match -> match.group(1)).toList();
    }

    private static List<String> tableRows(String html) {
        var body = Pattern.compile("<tbody>(.*?)</tbody>", Pattern.DOTALL).matcher(html);
        assertThat(body.find()).isTrue();
        return Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL).matcher(body.group(1)).results()
                .map(match -> match.group(1)).toList();
    }

    private long syntheticOperation(String filter) {
        jdbc.update("INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES(?,?,'GITHUB','github.com',?,?,'APPROVED')",
                "Synthetic operations " + filter, "https://github.com/fixture/" + filter, "fixture/" + filter, administratorId);
        long id = jdbc.queryForObject("SELECT id FROM project WHERE repository_path=?", Long.class, "fixture/" + filter);
        if (!filter.equals("NEVER_RUN")) {
            jdbc.update("INSERT INTO review_run(project_id,status,started_at,error_message) VALUES(?,'FAILED',TIMESTAMP '2000-01-01 00:00:00','private-operation-error')", id);
        }
        if (List.of("QUEUED", "REQUEST_DELAYED", "RATE_LIMITED").contains(filter)) {
            jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_at,available_at,result_code) VALUES(?,?,'QUEUED','SCHEDULED',TIMESTAMP '2000-01-01 00:00:00',CURRENT_TIMESTAMP,?)",
                    id, UUID.randomUUID().toString(), filter.equals("RATE_LIMITED") ? "AI_RATE_LIMITED" : null);
        }
        return id;
    }

    private Map<String, List<Map<String, Object>>> domainState() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : List.of("app_user", "project", "review_run", "review_request", "reviewed_commit", "review_issue", "manual_review_file", "audit_event", "git_author_mapping")) {
            state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + (table.equals("review_request") ? "project_id" : "id")));
        }
        return state;
    }
}
