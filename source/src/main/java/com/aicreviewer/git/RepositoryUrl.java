package com.aicreviewer.git;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** A clone-style URL normalized without making a network request. */
public record RepositoryUrl(String normalizedUrl, String provider, String host, String path) {
    public static RepositoryUrl parse(String input, Set<String> allowedHosts) {
        if (input == null || input.length() > 2048 || !input.equals(input.strip())) {
            throw new IllegalArgumentException("Repository URL is required and must be at most 2048 characters");
        }
        final URI uri;
        try { uri = URI.create(input); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid repository URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowed = allowedHosts.stream().map(s -> s.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!allowed.contains(host) || host.isBlank() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new IllegalArgumentException("Use an HTTP(S) repository URL on an allowed host without credentials, query or fragment");
        }
        boolean github = host.equals("github.com");
        if (github && (!scheme.equals("https") || (uri.getPort() != -1 && uri.getPort() != 443))) {
            throw new IllegalArgumentException("GitHub requires HTTPS on the standard port");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawPath() == null) {
            throw new IllegalArgumentException("Invalid repository URL");
        }
        String path = uri.getRawPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
        if (path.startsWith("/")) path = path.substring(1);
        String[] segments = path.split("/", -1);
        if (path.length() > 1024 || segments.length < 2 || (github && segments.length != 2)) {
            throw new IllegalArgumentException("Use the repository clone URL including its namespace and repository name");
        }
        for (String segment : segments) {
            if (!segment.matches("[A-Za-z0-9_.-]+") || segment.equals(".") || segment.equals("..") || segment.equals("-")) {
                throw new IllegalArgumentException("Invalid repository path segment");
            }
        }
        if (github) path = path.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String authority = host + ((port == -1 || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80)) ? "" : ":" + port);
        return new RepositoryUrl(scheme + "://" + authority + "/" + path, github ? "GITHUB" : "GITLAB", host, path);
    }
}
