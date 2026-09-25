package com.aicreviewer.git;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact-origin credential selection; repository URLs never control a credential's destination. */
final class GitCredentialRegistry {
    private final Map<String, String> tokens;
    private final Set<String> credentialHosts;

    GitCredentialRegistry(Set<String> allowedHosts, String legacyToken, String legacyHost, String legacyOrigin,
            GitCredentialProperties properties) {
        Map<String, String> configured = new HashMap<>();
        String validatedLegacyToken = SafeHttpTransport.credential(legacyToken);
        if (!validatedLegacyToken.isBlank()) {
            String origin = legacyOrigin == null || legacyOrigin.isBlank()
                    ? "https://" + (legacyHost == null ? "" : legacyHost.strip()) : legacyOrigin;
            put(configured, allowedHosts, origin, validatedLegacyToken);
        }
        if (properties == null || properties.getCredentials() == null || properties.getCredentials().size() > 100) {
            throw new IllegalArgumentException("Configure at most 100 Git credential entries");
        }
        for (GitCredentialProperties.Credential entry : properties.getCredentials()) {
            if (entry == null) throw new IllegalArgumentException("Git credential entries must include an origin and token");
            put(configured, allowedHosts, entry.getOrigin(), entry.getToken());
        }
        this.tokens = Map.copyOf(configured);
        this.credentialHosts = tokens.keySet().stream().map(origin -> URI.create(origin).getHost()).collect(Collectors.toUnmodifiableSet());
    }

    String tokenFor(RepositoryUrl repository) {
        String origin = RepositoryOrigin.fromRepository(repository);
        String token = tokens.get(origin);
        if (token != null) return token;
        if (credentialHosts.contains(URI.create(origin).getHost())) {
            throw new IntegrationException("Repository origin does not match a configured Git credential origin; configure its trusted scheme, host and port explicitly");
        }
        return null;
    }

    private static void put(Map<String, String> tokens, Set<String> allowedHosts, String origin, String token) {
        String validToken = SafeHttpTransport.credential(token);
        if (validToken.isBlank()) throw new IllegalArgumentException("Git credential entries must include a nonempty token");
        String canonical = RepositoryOrigin.normalize(origin, allowedHosts);
        if (tokens.putIfAbsent(canonical, validToken) != null) {
            throw new IllegalArgumentException("Multiple Git credentials are configured for the same origin");
        }
    }

    @Override public String toString() { return "GitCredentialRegistry[redacted]"; }
}
