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

    private Request request(String state, Instant requested, int attempts, String result) {
        return new Request(10, "private-request-identifier", state, "MANUAL", 987654321L,
                requested, NOW, NOW, attempts, 20L, null, result);
    }
}
