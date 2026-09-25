package com.aicreviewer.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A bounded, per-node rate limiter. Deployments with several nodes should also limit at their ingress. */
@Component
public class LoginAttemptLimiter {
    private final int accountLimit;
    private final int addressLimit;
    private final int maxEntries;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Bucket> attempts = new HashMap<>();

    @Autowired
    public LoginAttemptLimiter(@Value("${app.security.login-account-attempts:10}") int accountLimit,
                               @Value("${app.security.login-address-attempts:100}") int addressLimit,
                               @Value("${app.security.login-window-seconds:900}") int windowSeconds,
                               @Value("${app.security.login-max-entries:10000}") int maxEntries) {
        this(accountLimit, addressLimit, windowSeconds, maxEntries, Clock.systemUTC());
    }

    LoginAttemptLimiter(int accountLimit, int addressLimit, int windowSeconds, int maxEntries, Clock clock) {
        if (accountLimit < 1 || addressLimit < 1 || windowSeconds < 1 || windowSeconds > 86_400
                || maxEntries < 2 || maxEntries > 1_000_000) {
            throw new IllegalArgumentException("로그인 시도 제한 설정이 올바르지 않습니다.");
        }
        this.accountLimit = accountLimit;
        this.addressLimit = addressLimit;
        this.windowMillis = windowSeconds * 1000L;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public synchronized Decision acquire(String username, String address) {
        long now = clock.millis();
        attempts.values().removeIf(bucket -> bucket.expiresAt <= now);
        String accountKey = accountKey(username);
        String addressKey = "ip:" + digest(address == null ? "unknown" : address);
        Bucket account = attempts.get(accountKey);
        Bucket remote = attempts.get(addressKey);
        if (account != null && account.count >= accountLimit) return denied(account.expiresAt, now);
        if (remote != null && remote.count >= addressLimit) return denied(remote.expiresAt, now);
        int needed = (account == null ? 1 : 0) + (remote == null ? 1 : 0);
        if (attempts.size() + needed > maxEntries) {
            // Never evict a throttled account to make room for attacker-controlled new names.
            long earliest = attempts.values().stream().mapToLong(bucket -> bucket.expiresAt).min().orElse(now + windowMillis);
            return denied(earliest, now);
        }
        increment(accountKey, account, now);
        increment(addressKey, remote, now);
        return new Decision(true, 0);
    }

    public synchronized void succeeded(String username) { attempts.remove(accountKey(username)); }

    synchronized int entryCount() { return attempts.size(); }

    private void increment(String key, Bucket existing, long now) {
        attempts.put(key, existing == null ? new Bucket(1, now + windowMillis) : new Bucket(existing.count + 1, existing.expiresAt));
    }

    private static Decision denied(long expiry, long now) { return new Decision(false, Math.max(1, (expiry - now + 999) / 1000)); }

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
    private record Bucket(int count, long expiresAt) { }
}
