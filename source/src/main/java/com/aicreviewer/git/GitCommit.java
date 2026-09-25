package com.aicreviewer.git;

import java.util.List;

/** Author email comes from commit metadata and is not a verified identity claim. */
public record GitCommit(String sha, String authorLogin, String authorEmail, String message, String diff,
        String coverageType, String coverageDetails, List<ManualReviewFile> manualFiles) {
    public GitCommit {
        manualFiles = List.copyOf(manualFiles);
        if (manualFiles.size() > ManualReviewFile.MAX_FILES
                || ("MANUAL_ONLY".equals(coverageType) != !manualFiles.isEmpty())
                || manualFiles.stream().map(ManualReviewFile::filePath).distinct().count() != manualFiles.size()) {
            throw new IllegalArgumentException("Invalid manual review coverage");
        }
    }

    public GitCommit(String sha, String authorLogin, String authorEmail, String message, String diff,
            String coverageType, String coverageDetails) {
        this(sha, authorLogin, authorEmail, message, diff, coverageType, coverageDetails, List.of());
    }
    public GitCommit(String sha, String authorLogin, String authorEmail, String message, String diff) {
        this(sha, authorLogin, authorEmail, message, diff, "FULL", "");
    }

    public GitCommit(String sha, String authorLogin, String message, String diff) {
        this(sha, authorLogin, null, message, diff);
    }
}
