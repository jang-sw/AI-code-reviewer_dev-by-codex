package com.aicreviewer.project;

import java.util.Map;

/** Safe, field-specific messages; never includes a submitted URL or its credentials. */
public final class ProjectValidationException extends IllegalArgumentException {
    private final Map<String, String> fieldErrors;

    public ProjectValidationException(Map<String, String> fieldErrors) {
        super("입력한 내용을 확인해 주세요.");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
