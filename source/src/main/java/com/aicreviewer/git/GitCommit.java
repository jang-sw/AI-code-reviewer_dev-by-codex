package com.aicreviewer.git;

/** Author email comes from commit metadata and is not a verified identity claim. */
public record GitCommit(String sha, String authorLogin, String authorEmail, String message, String diff) {
    public GitCommit(String sha, String authorLogin, String message, String diff) {
        this(sha, authorLogin, null, message, diff);
    }
}
