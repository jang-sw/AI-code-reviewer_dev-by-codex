package com.aicreviewer.git;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/** RFC 9110 section 10.2.3, with explicit local automatic-retry safety bounds. */
final class RetryAfterPolicy {
    private static final long MAX_SECONDS = 86400;
    private static final DateTimeFormatter IMF = format("EEE, dd MMM uuuu HH:mm:ss 'GMT'");
    private static final DateTimeFormatter RFC850 = format("dd-MMM-uuuu HH:mm:ss 'GMT'");
    private static final DateTimeFormatter ASCTIME = format("EEE MMM d HH:mm:ss uuuu");
    private static final Pattern OLD_DATE = Pattern.compile("^([A-Za-z]+), ([0-9]{2}-[A-Za-z]{3}-)([0-9]{2})( [0-9]{2}:[0-9]{2}:[0-9]{2} GMT)$");

    private RetryAfterPolicy() { }

    static Instant retryAt(HttpHeaders headers, Instant receivedAt) {
        var values = headers.allValues("Retry-After");
        if (values.size() != 1) return receivedAt.plusSeconds(60);
        // An oversized value can be a valid enormous delay; never replace it with an early retry.
        if (values.getFirst().length() > 128) return null;
        String value = values.getFirst().strip();
        if (value.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(value);
                return seconds > MAX_SECONDS ? null : receivedAt.plusSeconds(Math.max(30, seconds));
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        try {
            Instant requested = httpDate(value, receivedAt);
            Duration delay = Duration.between(receivedAt, requested);
            if (delay.compareTo(Duration.ofSeconds(MAX_SECONDS)) > 0) return null;
            return delay.compareTo(Duration.ofSeconds(30)) < 0 ? receivedAt.plusSeconds(30) : requested;
        } catch (DateTimeParseException invalid) {
            return receivedAt.plusSeconds(60);
        }
    }

    private static Instant httpDate(String value, Instant receivedAt) {
        var old = OLD_DATE.matcher(value);
        if (old.matches()) {
            int currentYear = receivedAt.atOffset(ZoneOffset.UTC).getYear();
            int year = currentYear / 100 * 100 + Integer.parseInt(old.group(3));
            LocalDateTime candidate = LocalDateTime.parse(old.group(2) + year + old.group(4), RFC850);
            if (candidate.toInstant(ZoneOffset.UTC).isAfter(receivedAt.atOffset(ZoneOffset.UTC).plusYears(50).toInstant())) {
                candidate = candidate.minusYears(100);
            }
            if (!candidate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).equals(old.group(1))) {
                throw new DateTimeParseException("Invalid HTTP date", "", 0);
            }
            return candidate.toInstant(ZoneOffset.UTC);
        }
        if (value.matches("[A-Za-z]{3} [A-Za-z]{3} (?: [1-9]|[12][0-9]|3[01]) [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}")) {
            return LocalDateTime.parse(value.replace("  ", " "), ASCTIME).toInstant(ZoneOffset.UTC);
        }
        return LocalDateTime.parse(value, IMF).toInstant(ZoneOffset.UTC);
    }

    private static DateTimeFormatter format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }
}
