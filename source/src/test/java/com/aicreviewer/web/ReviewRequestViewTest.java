package com.aicreviewer.web;

import com.aicreviewer.review.ReviewRequestRepository.Request;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ReviewRequestViewTest {
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @ParameterizedTest @CsvSource({"QUEUED,true,접수 완료 · 실행 대기", "RUNNING,true,처리 중으로 기록됨",
            "SUCCEEDED,false,이번 요청 처리 완료", "FAILED,false,요청 처리 실패", "CANCELLED,false,요청 취소"})
    void statesAreExplicitAndRequestOwnershipDataIsOmitted(String state, boolean active, String label) {
        var view = ReviewRequestView.from(request(state, NOW.minusSeconds(120), 1, null), NOW);
        assertThat(view.stateLabel()).isEqualTo(label);
        assertThat(view.active()).isEqualTo(active);
        assertThat(view.sourceLabel()).isEqualTo("직접 요청");
        assertThat(view.elapsedMinutes()).isEqualTo(2);
        assertThat(view.recovered()).isFalse();
        assertThat(view.toString()).doesNotContain("private-request-identifier", "987654321", "requestedBy", "claim");
    }

    @Test void futureTimestampsDoNotProduceNegativeAgeAndRepeatedAttemptsAreDisclosed() {
        var view = ReviewRequestView.from(request("RUNNING", NOW.plusSeconds(60), 2, null), NOW);
        assertThat(view.elapsedMinutes()).isZero();
        assertThat(view.recovered()).isTrue();
        assertThat(view.stateLabel()).doesNotContain("복구 완료", "살아");
    }

    @ParameterizedTest @CsvSource({"PROJECT_INELIGIBLE,프로젝트", "REQUESTER_INELIGIBLE,요청자의 계정", "REVIEW_FAILED,완료하지 못", "BATCH_COMPLETED,이번 배치"})
    void resultCodesBecomeFixedActionableText(String code, String expected) {
        assertThat(ReviewRequestView.from(request("CANCELLED", NOW, 1, code), NOW).resultLabel()).contains(expected);
    }

    @Test void unknownResultTextIsNeverEchoed() {
        assertThat(ReviewRequestView.from(request("FAILED", NOW, 1, "private-provider-error"), NOW).resultLabel()).isEmpty();
    }

    @ParameterizedTest @CsvSource({"GIT_RATE_LIMITED,Git 서버", "AI_RATE_LIMITED,AI 서비스"})
    void queuedRateLimitShowsFixedServiceTextAndUtcRetryTimeWithoutPrivateMetadata(String code, String expected) {
        Instant retry = Instant.parse("2026-09-26T10:02:03.123456789Z");
        var queued = new Request(10, "private-request-identifier", "QUEUED", "MANUAL", 987654321L,
                NOW.minusSeconds(120), retry, NOW.minusSeconds(60), 2, null, null, code);
        var view = ReviewRequestView.from(queued, NOW);
        assertThat(view.rateLimited()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.stateLabel()).isEqualTo("호출 제한 · 재시도 대기");
        assertThat(view.rateLimitLabel()).isEqualTo(expected + " 호출 제한으로 재시도를 기다립니다.");
        assertThat(view.retryAt()).isEqualTo(retry);
        assertThat(view.retryAtLabel()).isEqualTo("2026-09-26 10:02:03");
        assertThat(view.toString()).doesNotContain("private-request-identifier", "987654321", code, "claim");
    }

    @ParameterizedTest @CsvSource({"-1", "0", "1"})
    void retryTimeBeforeAtOrAfterNowDoesNotInventAStartedOrCompletedRequest(long seconds) {
        var queued = new Request(10, "private", "QUEUED", "SCHEDULED", null,
                NOW.minusSeconds(120), NOW.plusSeconds(seconds), NOW.minusSeconds(60), 1, null, null, "AI_RATE_LIMITED");
        var view = ReviewRequestView.from(queued, NOW);
        assertThat(view.rateLimited()).isTrue();
        assertThat(view.state()).isEqualTo("QUEUED");
        assertThat(view.retryAt()).isEqualTo(NOW.plusSeconds(seconds));
        assertThat(view.stateLabel()).doesNotContain("완료", "실행 중");
    }

    @Test void ordinaryQueueAndRunningRecoveryNeverTreatAvailableAtAsRateLimiting() {
        for (String state : new String[] {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"}) {
            var view = ReviewRequestView.from(request(state, NOW, 2, "private-provider-error"), NOW);
            assertThat(view.rateLimited()).isFalse();
            assertThat(view.rateLimitLabel()).isEmpty();
            assertThat(view.retryAt()).isNull();
            assertThat(view.retryAtLabel()).isEmpty();
            assertThat(view.toString()).doesNotContain("private-provider-error");
        }
        var claimed = ReviewRequestView.from(request("RUNNING", NOW, 2, "AI_RATE_LIMITED"), NOW);
        assertThat(claimed.rateLimited()).isFalse();
        assertThat(claimed.retryAt()).isNull();
    }

    @Test void exhaustedRateLimitOffersManualActionOnlyForTerminalFailure() {
        var terminal = ReviewRequestView.from(request("FAILED", NOW, 6, "RATE_LIMIT_EXHAUSTED"), NOW);
        assertThat(terminal.active()).isFalse();
        assertThat(terminal.rateLimited()).isFalse();
        assertThat(terminal.rateLimitExhausted()).isTrue();
        assertThat(terminal.resultLabel()).contains("자동 재시도를 중단", "새 예약으로 자동 재접수하지 않습니다",
                "서비스 상태를 확인한 후 직접 다시 요청", "저장된 리뷰와 이슈는 유지");
        assertThat(terminal.retryAt()).isNull();
        assertThat(ReviewRequestView.from(request("QUEUED", NOW, 6, "RATE_LIMIT_EXHAUSTED"), NOW).resultLabel()).isEmpty();
    }

    @Test void manualRetryGuidanceRequiresTheExactTerminalStateAndCode() {
        for (String state : new String[] {"QUEUED", "RUNNING", "SUCCEEDED", "CANCELLED"}) {
            assertThat(ReviewRequestView.from(request(state, NOW, 6, "RATE_LIMIT_EXHAUSTED"), NOW).rateLimitExhausted()).isFalse();
        }
        for (String code : new String[] {null, "REVIEW_FAILED", "AI_RATE_LIMITED", "private-provider-error"}) {
            assertThat(ReviewRequestView.from(request("FAILED", NOW, 6, code), NOW).rateLimitExhausted()).isFalse();
        }
    }

    private Request request(String state, Instant requested, int attempts, String result) {
        return new Request(10, "private-request-identifier", state, "MANUAL", 987654321L,
                requested, NOW, NOW, attempts, 20L, null, result);
    }
}
