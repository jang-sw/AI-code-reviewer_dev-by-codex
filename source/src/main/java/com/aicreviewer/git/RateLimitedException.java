package com.aicreviewer.git;

import java.time.Instant;
import java.util.Objects;

/** Contains only safe classification and a bounded retry time; never remote response data. */
public final class RateLimitedException extends IntegrationException {
    public enum Service { GIT, AI }

    private final Service service;
    private final Instant retryAt;
    private final boolean actualResponse;

    public RateLimitedException(Service service, Instant retryAt, boolean actualResponse) {
        super(Objects.requireNonNull(service) == Service.GIT ? "Git returned HTTP 429" : "AI returned HTTP 429");
        this.service = service;
        this.retryAt = retryAt;
        this.actualResponse = actualResponse;
    }

    public Service service() { return service; }
    /** Null means the server delay or header length exceeds the automatic retry policy. */
    public Instant retryAt() { return retryAt; }
    public boolean actualResponse() { return actualResponse; }
}
