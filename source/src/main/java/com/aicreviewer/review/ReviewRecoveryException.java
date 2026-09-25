package com.aicreviewer.review;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Only these application-owned messages may be shown by the recovery error page. */
public final class ReviewRecoveryException extends ResponseStatusException {
    private final Failure failure;

    public ReviewRecoveryException(Failure failure) {
        super(failure.status, failure.message);
        this.failure = failure;
    }

    public String safeMessage() { return failure.message; }

    public enum Failure {
        BUSY(HttpStatus.CONFLICT, "리뷰가 실행 중이어서 진행 기준을 복구할 수 없습니다. 실행이 끝난 뒤 프로젝트 화면을 새로고침하고 다시 시도해 주세요."),
        NOT_PAUSED(HttpStatus.CONFLICT, "프로젝트 리뷰를 먼저 일시 중지해 주세요. 일시 중지 상태에서만 진행 기준을 복구할 수 있습니다."),
        STALE_CURSOR(HttpStatus.CONFLICT, "리뷰 진행 기준이 변경되었거나 이미 초기화되었습니다. 프로젝트 화면을 새로고침하고 현재 진행 기준을 확인해 주세요."),
        REPOSITORY_MISMATCH(HttpStatus.BAD_REQUEST, "입력한 저장소 주소가 프로젝트 주소와 일치하지 않습니다. 프로젝트 화면에 표시된 주소를 그대로 입력해 주세요."),
        INVALID_CONFIRMATION(HttpStatus.BAD_REQUEST, "저장소 주소와 리뷰 진행 기준을 확인할 수 없습니다. 프로젝트 화면을 새로고침한 뒤 다시 입력해 주세요."),
        INVALID_REASON(HttpStatus.BAD_REQUEST, "복구 사유는 줄바꿈이나 제어 문자 없이 5~500자로 입력해 주세요. 비밀번호·토큰 등 비밀정보는 입력하지 마세요.");

        private final HttpStatus status;
        private final String message;

        Failure(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
