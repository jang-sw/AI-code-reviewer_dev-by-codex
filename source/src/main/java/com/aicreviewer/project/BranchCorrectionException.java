package com.aicreviewer.project;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Fixed messages only; submitted configuration and audit reasons never enter exceptions. */
public final class BranchCorrectionException extends ResponseStatusException {
    public enum Failure {
        INVALID_BRANCH(HttpStatus.BAD_REQUEST, "브랜치 이름을 확인해 주세요. 공백이나 '..' 같은 문자는 사용할 수 없습니다."),
        INVALID_REASON(HttpStatus.BAD_REQUEST, "정정 사유는 앞뒤 공백을 제외하고 5자 이상, 전체 500자 이하로 줄바꿈 없이 입력해 주세요."),
        INVALID_CONFIRMATION(HttpStatus.BAD_REQUEST, "현재 브랜치와 진행 기준을 확인하고 기록 보존 안내에 동의해 주세요."),
        BUSY(HttpStatus.CONFLICT, "현재 리뷰 작업이 프로젝트를 사용 중입니다. 작업이 끝난 뒤 새로고침하여 다시 확인해 주세요."),
        ACTIVE_REQUEST(HttpStatus.CONFLICT, "아직 처리 중이거나 대기 중인 리뷰 요청이 남아 있습니다. 요청 처리가 종료된 뒤 다시 확인해 주세요."),
        INVALID_STATE(HttpStatus.CONFLICT, "실행 전 승인 대기·반려 프로젝트 또는 리뷰가 일시 중지된 프로젝트만 브랜치를 정정할 수 있습니다."),
        STALE(HttpStatus.CONFLICT, "화면을 연 뒤 브랜치 또는 진행 기준이 변경되었습니다. 현재 정보를 다시 확인해 주세요."),
        UNCHANGED(HttpStatus.CONFLICT, "현재 브랜치와 같습니다. 다른 브랜치를 입력하거나 기본 브랜치를 선택하려면 입력을 비워 주세요.");

        final HttpStatus status;
        final String message;
        Failure(HttpStatus status, String message) { this.status = status; this.message = message; }
    }

    public BranchCorrectionException(Failure failure) { super(failure.status, failure.message); }
    public String safeMessage() { return getReason(); }
}
