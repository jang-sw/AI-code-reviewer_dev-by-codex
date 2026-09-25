package com.aicreviewer.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/** Real Tomcat/JSP rendering and log capture; synthetic SQL failures never contact an external DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:web_exception_privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false"
})
@ActiveProfiles({"test", "web-error-fixture"})
@Import(WebExceptionPrivacyTest.Fixtures.class)
class WebExceptionPrivacyTest {
    private static final String PRIVATE = "PRIVATE_DATABASE_ROW_SOURCE_PASSWORD_FIXTURE";
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Logger root;

    @BeforeEach void capture() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        events.start();
        root.addAppender(events);
    }

    @AfterEach void stopCapture() { root.detachAppender(events); events.stop(); }

    @Test
    void databaseFailureRenders500WithoutSqlCauseOrSubmittedValuesInAnyCapturedLog() throws Exception {
        var result = get("database?reason=" + PRIVATE);
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.body()).contains("요청을 처리하지 못했습니다", "HTTP 500")
                .doesNotContain(PRIVATE, "INSERT INTO", "Failing row contains", "SQLException", "DataIntegrityViolationException");
        var safe = events.list.stream().filter(event -> event.getLoggerName().equals(SafeUnexpectedExceptionResolver.class.getName())).toList();
        assertThat(safe).hasSize(1);
        assertThat(safe.getFirst().getFormattedMessage()).matches(
                "Unexpected web request failure: type=DataIntegrityViolationException reference=[0-9a-f-]{36}");
        assertThat(safe.getFirst().getThrowableProxy()).isNull();
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE, "INSERT INTO", "Failing row contains");
            if (event.getThrowableProxy() != null) {
                assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(PRIVATE);
            }
        });
    }

    @Test
    void frameworkValidationAndDeclaredStatusHandlersKeepTheirOriginalHttpStatuses() throws Exception {
        assertThat(get("conflict").statusCode()).isEqualTo(409);
        assertThat(get("annotated").statusCode()).isEqualTo(422);
        assertThat(get("number").statusCode()).isEqualTo(400);
        assertThat(get("number?count=not-an-integer").statusCode()).isEqualTo(400);
        assertThat(get("number?count=0").statusCode()).isEqualTo(400);
        assertThat(get("post-only").statusCode()).isEqualTo(405);
        assertThat(client.send(HttpRequest.newBuilder(uri("number")).method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403); // Existing CSRF boundary precedes MVC.
        assertThat(events.list).noneSatisfy(event -> assertThat(event.getLoggerName()).isEqualTo(SafeUnexpectedExceptionResolver.class.getName()));
    }

    @Test
    void securityExceptionsStillReachTheExistingAuthenticationEntryPoint() throws Exception {
        for (String path : new String[] { "denied", "authentication", "wrapped-denied" }) {
            var result = get(path);
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
        }
        assertThat(events.list).noneSatisfy(event -> assertThat(event.getLoggerName()).isEqualTo(SafeUnexpectedExceptionResolver.class.getName()));
    }

    private URI uri(String suffix) { return URI.create("http://127.0.0.1:" + port + "/css/web-error-fixture/" + suffix); }
    private HttpResponse<String> get(String suffix) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(suffix)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        // This nested component is registered once by the imported test configuration.
        // Its dedicated profile keeps the fixture out of every ordinary application/test context.
        @Controller
        @Profile("web-error-fixture")
        class FailureController {
            @GetMapping("/css/web-error-fixture/database")
            @ResponseBody String database(@RequestParam String reason) {
                throw new DataIntegrityViolationException("INSERT INTO fixture(value) VALUES ('" + reason + "')",
                        new SQLException("Failing row contains (" + reason + ")", "23514"));
            }
            @GetMapping("/css/web-error-fixture/conflict")
            @ResponseBody String conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "fixed fixture conflict"); }
            @GetMapping("/css/web-error-fixture/annotated")
            @ResponseBody String annotated() { throw new DeclaredStatusException(); }
            @GetMapping("/css/web-error-fixture/number")
            @ResponseBody String number(@RequestParam @Min(1) int count) { return Integer.toString(count); }
            @PostMapping("/css/web-error-fixture/post-only")
            @ResponseBody String postOnly() { return "unused"; }
            @GetMapping("/css/web-error-fixture/denied")
            @ResponseBody String denied() { throw new AccessDeniedException("fixed denied fixture"); }
            @GetMapping("/css/web-error-fixture/authentication")
            @ResponseBody String authentication() { throw new BadCredentialsException("fixed authentication fixture"); }
            @GetMapping("/css/web-error-fixture/wrapped-denied")
            @ResponseBody String wrappedDenied() throws ServletException { throw new ServletException(new AccessDeniedException("fixed denied fixture")); }
        }
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    static class DeclaredStatusException extends RuntimeException { }
}
