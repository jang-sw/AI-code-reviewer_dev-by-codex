package com.aicreviewer.identity;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Only fixed, field-specific messages; submitted values and database causes are not retained. */
public final class GitAuthorMappingValidationException extends IllegalArgumentException {
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    GitAuthorMappingValidationException(HttpStatus status, String field, String message) {
        super(message);
        this.status = status;
        this.fieldErrors = Map.of(field, message);
    }

    public HttpStatus status() { return status; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
