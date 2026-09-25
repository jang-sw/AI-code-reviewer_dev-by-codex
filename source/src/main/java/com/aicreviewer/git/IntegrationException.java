package com.aicreviewer.git;

/** Messages are deliberately safe to persist: never include URLs, credentials or remote bodies. */
public class IntegrationException extends RuntimeException {
    public IntegrationException(String message) { super(message); }
}
