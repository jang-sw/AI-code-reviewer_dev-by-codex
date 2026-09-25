package com.aicreviewer.git;

public record GitCommit(String sha, String authorLogin, String message, String diff) { }
