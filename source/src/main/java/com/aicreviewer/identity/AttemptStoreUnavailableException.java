package com.aicreviewer.identity;

/** A fixed diagnostic without database statements, parameters or an underlying cause. */
public final class AttemptStoreUnavailableException extends RuntimeException {
    public AttemptStoreUnavailableException() {
        super("Shared authentication attempt storage is unavailable.", null);
    }
}
