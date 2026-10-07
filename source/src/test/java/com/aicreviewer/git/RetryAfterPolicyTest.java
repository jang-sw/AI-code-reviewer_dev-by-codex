package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private Instant retry(String... values) {
        return RetryAfterPolicy.retryAt(HttpHeaders.of(Map.of("rEtRy-AfTeR", List.of(values)), (name, value) -> true), NOW);
    }

    @ParameterizedTest @CsvSource({"0,30", "1,30", "29,30", "30,30", "90,90", "86400,86400", "000060,60"})
    void boundsNumericDelayWithoutDiscardingValidValues(String header, long delay) {
        assertThat(retry(header)).isEqualTo(NOW.plusSeconds(delay));
    }

    @Test void handlesDateFuturePastAndExactMaximum() {
        for (long seconds : List.of(-60L, 0L, 10L, 90L, 86400L)) {
            String date = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .format(NOW.plusSeconds(seconds).atZone(ZoneOffset.UTC));
            assertThat(retry(date)).isEqualTo(NOW.plusSeconds(Math.max(30, seconds)));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "-1", "+20", "1.5", "2e3", "60, 90", "PRIVATE malformed fixture", "Wed, 99 Oct 2026 12:00:00 GMT"})
    void malformedSingleValuesUseTheSafeDefault(String header) {
        assertThat(retry(header)).isEqualTo(NOW.plusSeconds(60));
    }

    @Test void missingAndDuplicateHeadersUseTheDefaultButOverlongValuesRequireManualResubmission() {
        assertThat(retry()).isEqualTo(NOW.plusSeconds(60));
        assertThat(retry("30", "90")).isEqualTo(NOW.plusSeconds(60));
        assertThat(retry("9".repeat(129))).isNull();
        assertThat(retry("x".repeat(129))).isNull();
        assertThat(retry(" 90 ")).isEqualTo(NOW.plusSeconds(90));
    }

    @ParameterizedTest @ValueSource(strings = {"86401", "9223372036854775807", "9223372036854775808", "999999999999999999999999999999999999"})
    void validNumericDelayBeyondThePolicyNeverRetriesEarly(String header) {
        assertThat(retry(header)).isNull();
    }

    @Test void futureDatesBeyondOneDayRequireManualResubmission() {
        assertThat(retry("Thu, 08 Oct 2026 12:00:01 GMT")).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"Wednesday, 07-Oct-26 12:01:30 GMT", "Wed Oct  7 12:01:30 2026"})
    void acceptsObsoleteHttpDatesRequiredForRecipients(String header) {
        assertThat(retry(header)).isEqualTo(NOW.plusSeconds(90));
    }

    @Test void obsoleteTwoDigitYearsUseTheFiftyYearRuleAndVerifyTheWeekday() {
        // 2077 would be more than fifty years ahead, so interpret 77 as 1977 (Friday).
        assertThat(retry("Friday, 07-Oct-77 12:00:00 GMT")).isEqualTo(NOW.plusSeconds(30));
        assertThat(retry("Thursday, 07-Oct-77 12:00:00 GMT")).isEqualTo(NOW.plusSeconds(60));
        // A valid 2076 date less than fifty years ahead still exceeds the 24-hour policy.
        String weekday = Instant.parse("2076-10-07T12:00:00Z").atOffset(ZoneOffset.UTC).getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        assertThat(retry(weekday + ", 07-Oct-76 12:00:00 GMT")).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"Wed, 07 Oct 2026 12:01:30 +0000", "Wed, 07 Oct 2026 12:01:30 UTC",
            "Thu, 07 Oct 2026 12:01:30 GMT", "Wed, 07 Oct 2026 25:00:00 GMT"})
    void malformedDatesDoNotBecomeVeryLongAutomaticCooldowns(String header) {
        assertThat(retry(header)).isEqualTo(NOW.plusSeconds(60));
    }
}
