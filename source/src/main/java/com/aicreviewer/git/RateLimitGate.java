package com.aicreviewer.git;

import java.net.URI;
import java.time.Instant;

/** An implementation may persist shared cooldowns; failures must propagate to the caller. */
public interface RateLimitGate {
    RateLimitGate NOOP = new RateLimitGate() {
        @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { }
        @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { }
    };

    void beforeRequest(RateLimitedException.Service service, URI uri);
    void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt);
}
