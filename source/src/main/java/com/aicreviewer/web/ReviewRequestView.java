package com.aicreviewer.web;

import com.aicreviewer.review.ReviewRequestRepository.Request;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** An authorized request snapshot only; ownership tokens and account identifiers never enter a view. */
public record ReviewRequestView(String state, String stateLabel, String sourceLabel, Instant requestedAt,
                                Instant lastAttemptAt, int attemptCount, Instant finishedAt,
                                String resultLabel, long elapsedMinutes, boolean active, boolean recovered,
                                boolean rateLimited, String rateLimitLabel, Instant retryAt, boolean rateLimitExhausted) {
    private static final DateTimeFormatter UTC_SECONDS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    public static ReviewRequestView from(Request request, Instant observedAt) {
        String rateLimitLabel = "QUEUED".equals(request.state()) ? switch (request.resultCode() == null ? "" : request.resultCode()) {
            case "GIT_RATE_LIMITED" -> "Git 서버 호출 제한으로 재시도를 기다립니다.";
            case "AI_RATE_LIMITED" -> "AI 서비스 호출 제한으로 재시도를 기다립니다.";
            default -> "";
        } : "";
        boolean rateLimited = !rateLimitLabel.isEmpty();
        String label = switch (request.state()) {
            case "QUEUED" -> rateLimited ? "호출 제한 · 재시도 대기" : "접수 완료 · 실행 대기";
            case "RUNNING" -> "처리 중으로 기록됨";
            case "SUCCEEDED" -> "이번 요청 처리 완료";
            case "FAILED" -> "요청 처리 실패";
            case "CANCELLED" -> "요청 취소";
            default -> "요청 상태 확인 필요";
        };
        String result = switch (request.resultCode() == null ? "" : request.resultCode()) {
            case "PROJECT_INELIGIBLE" -> "프로젝트가 승인 상태가 아니어서 요청을 취소했습니다.";
            case "REQUESTER_INELIGIBLE" -> "요청자의 계정 상태 또는 프로젝트 접근 권한이 변경되어 요청을 취소했습니다.";
            case "REVIEW_FAILED" -> "리뷰 처리를 완료하지 못했습니다. 리뷰 실행 기록을 확인한 뒤 다시 요청해 주세요.";
            case "BATCH_COMPLETED" -> "이번 배치를 처리했습니다. 남은 이력과 수동 확인 업무는 별도로 확인해 주세요.";
            case "RATE_LIMIT_EXHAUSTED" -> "FAILED".equals(request.state())
                    ? "자동 재시도를 중단했습니다. 새 예약으로 자동 재접수하지 않습니다. 서비스 상태를 확인한 후 직접 다시 요청해 주세요. 이미 저장된 리뷰와 이슈는 유지됩니다." : "";
            default -> "";
        };
        return new ReviewRequestView(request.state(), label, "MANUAL".equals(request.source()) ? "직접 요청" : "예약 요청",
                request.requestedAt(), request.lastAttemptAt(), request.attemptCount(), request.finishedAt(), result,
                Math.max(0, Duration.between(request.requestedAt(), observedAt).toMinutes()), request.active(), request.attemptCount() > 1,
                rateLimited, rateLimitLabel, rateLimited ? request.availableAt() : null,
                "FAILED".equals(request.state()) && "RATE_LIMIT_EXHAUSTED".equals(request.resultCode()));
    }

    public String retryAtLabel() { return retryAt == null ? "" : UTC_SECONDS.format(retryAt); }
}
