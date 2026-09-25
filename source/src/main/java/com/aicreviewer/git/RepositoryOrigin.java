package com.aicreviewer.git;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Canonical exact origin shared by credential selection and account mappings. */
public final class RepositoryOrigin {
    private RepositoryOrigin() { }

    public static String normalize(String origin, Set<String> allowedHosts) {
        URI uri = checkedUri(origin);
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
            throw new IllegalArgumentException("Repository origin must contain only scheme, host and optional port");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts == null || allowedHosts.stream().noneMatch(allowed -> allowed != null && allowed.strip().equalsIgnoreCase(host))) {
            throw new IllegalArgumentException("Repository origin host is not allowed");
        }
        return canonical(uri);
    }

    public static String fromRepositoryUrl(String repositoryUrl) {
        return canonical(checkedUri(repositoryUrl));
    }

    public static String fromRepository(RepositoryUrl repository) {
        if (repository == null) throw new IllegalArgumentException("Repository URL is required");
        return fromRepositoryUrl(repository.normalizedUrl());
    }

    private static URI checkedUri(String value) {
        if (value == null || value.isBlank() || value.length() > 2048 || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Invalid repository origin");
        }
        final URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid repository origin"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || host.isBlank()
                || host.contains(":") || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("Use an HTTP(S) origin with a DNS name or IPv4 host, a valid port, and no credentials, query or fragment");
        }
        if (host.equalsIgnoreCase("github.com") && (!scheme.equals("https") || (uri.getPort() != -1 && uri.getPort() != 443))) {
            throw new IllegalArgumentException("GitHub requires HTTPS on the standard port");
        }
        return uri;
    }

    private static String canonical(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
