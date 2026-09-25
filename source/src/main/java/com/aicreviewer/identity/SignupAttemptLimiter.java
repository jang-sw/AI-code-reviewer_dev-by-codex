package com.aicreviewer.identity;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate bounded per-node buckets; successful signups never reset the address quota. */
@Component
public final class SignupAttemptLimiter {
    private final LoginAttemptLimiter buckets;

    @Autowired
    public SignupAttemptLimiter(@Value("${app.security.signup-address-attempts:10}") int attempts,
                                @Value("${app.security.signup-window-seconds:900}") int windowSeconds,
                                @Value("${app.security.signup-max-entries:10000}") int maxEntries) {
        this(attempts, windowSeconds, maxEntries, Clock.systemUTC());
    }

    SignupAttemptLimiter(int attempts, int windowSeconds, int maxEntries, Clock clock) {
        buckets = new LoginAttemptLimiter(attempts, attempts, windowSeconds, maxEntries, clock);
    }

    public LoginAttemptLimiter.Decision acquire(String address) {
        String key = address == null ? "unknown" : address;
        return buckets.acquire(key, key);
    }
}
