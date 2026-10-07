package com.aicreviewer.issue;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Raised only after the caller's access to the manual issue has been checked. */
public final class ManualIssueReasonException extends ResponseStatusException {
    public static final String SAFE_MESSAGE = "수동 확인 사유는 앞뒤 공백을 제외하고 줄바꿈 없이 5~1000자로 입력해 주세요.";

    public ManualIssueReasonException() {
        super(HttpStatus.BAD_REQUEST, SAFE_MESSAGE);
    }
}
