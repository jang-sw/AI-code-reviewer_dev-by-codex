package com.aicreviewer.git;

import java.net.URI;
import java.util.Set;

/** Builds browser links only from repository registration data and immutable commit IDs. */
public final class GitCommitLink {
    private GitCommitLink() { }

    /** Invalid legacy or unexpected stored data is displayed without a link. No request is made. */
    public static String from(String repositoryUrl, String commitSha) {
        if (repositoryUrl == null || commitSha == null || !commitSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) return null;
        try {
            String host = URI.create(repositoryUrl).getHost();
            if (host == null) return null;
            // The host was authorized at registration. Parsing here checks safe link syntax,
            // without granting access to the project or contacting the external Git server.
            RepositoryUrl repository = RepositoryUrl.parse(repositoryUrl, Set.of(host));
            return repository.normalizedUrl() + ("GITHUB".equals(repository.provider()) ? "/commit/" : "/-/commit/") + commitSha;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
