package com.aicreviewer.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed windows shared by every server using this database. Only hashed keys reach the store. */
@Component
public class LoginAttemptLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private final SharedAttemptStore store;
    private final String scope;
    private final int accountLimit;
    private final int addressLimit;
    private final int maxEntries;
    private final int windowSeconds;

    @Autowired
    public LoginAttemptLimiter(SharedAttemptStore store,
                               @Value("${app.security.login-account-attempts:10}") int accountLimit,
                               @Value("${app.security.login-address-attempts:100}") int addressLimit,
                               @Value("${app.security.login-window-seconds:900}") int windowSeconds,
                               @Value("${app.security.login-max-entries:10000}") int maxEntries) {
        this("LOGIN", store, accountLimit, addressLimit, windowSeconds, maxEntries);
    }

    LoginAttemptLimiter(String scope, SharedAttemptStore store, int accountLimit, int addressLimit, int windowSeconds, int maxEntries) {
        if (accountLimit < 1 || addressLimit < 1 || windowSeconds < 1 || windowSeconds > 86_400
                || maxEntries < 2 || maxEntries > 1_000_000) {
            throw new IllegalArgumentException("로그인 시도 제한 설정이 올바르지 않습니다.");
        }
        this.accountLimit = accountLimit;
        this.addressLimit = addressLimit;
        this.windowSeconds = windowSeconds;
        this.maxEntries = maxEntries;
        this.store = java.util.Objects.requireNonNull(store);
        this.scope = scope;
    }

    public Decision acquire(String username, String address) {
        return store.acquire(scope, accountKey(username), "ip:" + digest(address == null ? "unknown" : address),
                accountLimit, addressLimit, windowSeconds, maxEntries);
    }

    public void succeeded(String username) {
        try {
            store.clearAccount(scope, accountKey(username));
        } catch (AttemptStoreUnavailableException unavailable) {
            // Authentication already succeeded after consuming a durable attempt.
            // Keeping the quota is conservative; do not report failure with a live authenticated session.
            log.warn("Login attempt reset unavailable; previously consumed quota remains in force");
        }
    }

    private static String accountKey(String username) {
        // UsernamePasswordAuthenticationFilter trims ASCII first; our UserDetailsService then strips Unicode whitespace.
        return "account:" + digest(username == null ? "" : username.trim().strip().toLowerCase(Locale.ROOT));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) { }
}
