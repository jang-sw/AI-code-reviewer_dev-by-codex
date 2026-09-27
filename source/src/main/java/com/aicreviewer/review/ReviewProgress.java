package com.aicreviewer.review;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Persisted facts for one request's exact execution, without repository or ownership data. */
public record ReviewProgress(String stage, Instant recordedAt, int savedCommits, Instant lastSavedAt) {
    private static final DateTimeFormatter UTC_SECONDS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    public String stageLabel() {
        if (stage == null) return "단계 기록 없음";
        return switch (stage) {
            case "PREPARING" -> "저장된 리뷰 기록 확인";
            case "GIT_LOADING" -> "Git 이력·변경 확인";
            case "REVIEWING" -> "커밋 리뷰·수동 확인 준비";
            case "FINALIZING" -> "이번 실행 결과 정리";
            default -> "단계 기록 확인 필요";
        };
    }

    public String recordedAtLabel() { return recordedAt == null ? "" : UTC_SECONDS.format(recordedAt); }
    public String lastSavedAtLabel() { return lastSavedAt == null ? "" : UTC_SECONDS.format(lastSavedAt); }
}
