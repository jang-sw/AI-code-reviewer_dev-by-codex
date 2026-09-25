package com.aicreviewer.ai;

import com.aicreviewer.git.IntegrationException;

/** Only proven preflight input limits; messages never include source text or credentials. */
public final class AiInputLimitException extends IntegrationException {
    public enum Reason {
        TOTAL_DIFF_BYTES("Commit diff exceeds AI input size limit"),
        FILE_CONTEXT_BUDGET("A complete file exceeds the configured AI context budget"),
        MAX_REVIEW_CALLS("Complete review input exceeds the configured AI review call limit"),
        COMMIT_CONTEXT_BUDGET("Commit metadata exceeds the configured AI context budget");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    private final Reason reason;

    public AiInputLimitException(Reason reason) {
        super(java.util.Objects.requireNonNull(reason).message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
