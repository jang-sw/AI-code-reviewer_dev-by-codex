package com.aicreviewer.identity;

import java.time.Instant;

/** Only administrator screens expose claimed commit-email addresses. */
public record GitAuthorMapping(long id, long userId, String username, boolean userEnabled,
                               String repositoryOrigin, String authorEmail, Instant createdAt) { }
