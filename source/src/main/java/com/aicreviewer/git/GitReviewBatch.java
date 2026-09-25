package com.aicreviewer.git;

/**
 * Commits still needing review and the safe first-parent checkpoint after all of them succeed.
 * A partial merge group may have no checkpoint; an entirely persisted group may need no commits.
 * The checkpoint need not be the last selected commit: it can already exist in durable reviews.
 */
public record GitReviewBatch(java.util.List<GitCommit> commits, String checkpointSha) { }
