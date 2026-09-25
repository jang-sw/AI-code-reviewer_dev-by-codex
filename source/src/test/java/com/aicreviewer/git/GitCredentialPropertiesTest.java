package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.env.StandardEnvironment;

class GitCredentialPropertiesTest {
    private static final Set<String> HOSTS = Set.of("github.com", "git.internal");
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test void bindsSeveralOriginCredentialsAndSelectsTheExactOrigin() {
        context.withPropertyValues("app.git.credentials[0].origin=https://github.com:443/",
                "app.git.credentials[0].token=github-fixture-secret", "app.git.credentials[1].origin=https://git.internal:8443",
                "app.git.credentials[1].token=gitlab-fixture-secret").run(application -> {
            assertThat(application).hasNotFailed();
            GitCredentialProperties properties = application.getBean(GitCredentialProperties.class);
            assertThat(properties.getCredentials()).hasSize(2);
            GitCredentialRegistry registry = registry("", properties);
            assertThat(registry.tokenFor(repo("https://github.com/team/repo"))).isEqualTo("github-fixture-secret");
            assertThat(registry.tokenFor(repo("https://git.internal:8443/team/repo"))).isEqualTo("gitlab-fixture-secret");
            assertThatThrownBy(() -> registry.tokenFor(repo("http://git.internal:8443/team/repo"))).hasMessageContaining("credential origin");
            assertThat(properties.toString()).doesNotContain("github-fixture-secret", "gitlab-fixture-secret");
            assertThat(properties.getCredentials().toString()).doesNotContain("github-fixture-secret", "gitlab-fixture-secret");
            assertThat(registry.toString()).doesNotContain("github-fixture-secret", "gitlab-fixture-secret");
        });
    }

    @Test void legacyAndListCredentialsCoexistForDifferentOrigins() {
        GitCredentialProperties properties = properties(credential("https://git.internal", "gitlab-fixture-secret"));
        GitCredentialRegistry registry = registry("legacy-fixture-secret", properties);
        assertThat(registry.tokenFor(repo("https://github.com/team/repo"))).isEqualTo("legacy-fixture-secret");
        assertThat(registry.tokenFor(repo("https://git.internal/team/repo"))).isEqualTo("gitlab-fixture-secret");
        assertThat(registry("", new GitCredentialProperties()).tokenFor(repo("https://git.internal/team/repo"))).isNull();
    }

    @Test void bindsIndexedCredentialsFromDeploymentEnvironmentNames() {
        context.withInitializer(application -> application.getEnvironment().getPropertySources().addFirst(
                // Boot selects its environment-name mapper by both source type and standard source name.
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "APP_GIT_CREDENTIALS_0_ORIGIN", "https://github.com",
                        "APP_GIT_CREDENTIALS_0_TOKEN", "environment-first-fixture-secret",
                        "APP_GIT_CREDENTIALS_1_ORIGIN", "https://git.internal:8443",
                        "APP_GIT_CREDENTIALS_1_TOKEN", "environment-second-fixture-secret"))))
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    GitCredentialProperties properties = application.getBean(GitCredentialProperties.class);
                    assertThat(properties.getCredentials()).hasSize(2);
                    GitCredentialRegistry registry = registry("", properties);
                    assertThat(registry.tokenFor(repo("https://github.com/team/repo"))).isEqualTo("environment-first-fixture-secret");
                    assertThat(registry.tokenFor(repo("https://git.internal:8443/team/repo"))).isEqualTo("environment-second-fixture-secret");
                });
    }

    @Test void duplicateCanonicalOriginsAndLegacyCollisionsFailWithoutExposingTokens() {
        GitCredentialProperties duplicate = properties(credential("https://git.internal", "first-fixture-secret"),
                credential("HTTPS://Git.Internal:443/", "second-fixture-secret"));
        assertThatThrownBy(() -> registry("", duplicate)).hasMessage("Multiple Git credentials are configured for the same origin");
        GitCredentialProperties collision = properties(credential("https://github.com", "list-fixture-secret"));
        assertThatThrownBy(() -> registry("legacy-fixture-secret", collision))
                .hasMessage("Multiple Git credentials are configured for the same origin");
    }

    @Test void malformedEntriesFailWithSafeMessagesAndSafeRepresentations() {
        for (GitCredentialProperties.Credential entry : List.of(credential("https://SECRET@git.internal", "fixture-secret"),
                credential("https://outside.internal", "fixture-secret"), credential("https://git.internal", "fixture-secret\n"),
                credential("https://git.internal", ""), credential("https://git.internal", null), credential(null, "fixture-secret"))) {
            assertThatThrownBy(() -> registry("", properties(entry))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("SECRET").hasMessageNotContaining("fixture-secret");
            assertThat(entry.toString()).doesNotContain("SECRET", "fixture-secret");
        }
    }

    private static RepositoryUrl repo(String url) { return RepositoryUrl.parse(url, HOSTS); }
    private static GitCredentialRegistry registry(String legacyToken, GitCredentialProperties properties) {
        return new GitCredentialRegistry(HOSTS, legacyToken, "github.com", "", properties);
    }
    static GitCredentialProperties properties(GitCredentialProperties.Credential... credentials) {
        GitCredentialProperties properties = new GitCredentialProperties();
        properties.setCredentials(List.of(credentials));
        return properties;
    }
    static GitCredentialProperties.Credential credential(String origin, String token) {
        GitCredentialProperties.Credential credential = new GitCredentialProperties.Credential();
        credential.setOrigin(origin);
        credential.setToken(token);
        return credential;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitCredentialProperties.class)
    static class Binding { }
}
