package com.aicreviewer.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate shared buckets; successful signups never reset the address quota. */
@Component
public final class SignupAttemptLimiter {
    private final LoginAttemptLimiter buckets;

    @Autowired
    public SignupAttemptLimiter(SharedAttemptStore store,
                                @Value("${app.security.signup-address-attempts:10}") int attempts,
                                @Value("${app.security.signup-window-seconds:900}") int windowSeconds,
                                @Value("${app.security.signup-max-entries:10000}") int maxEntries) {
        // Preserve the existing two-bucket capacity accounting for each signup address.
        buckets = new LoginAttemptLimiter("SIGNUP", store, attempts, attempts, windowSeconds, maxEntries);
    }

    public LoginAttemptLimiter.Decision acquire(String address) {
        String key = address == null ? "unknown" : address;
        return buckets.acquire(key, key);
    }
}
