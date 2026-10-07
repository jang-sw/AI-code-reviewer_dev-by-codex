ALTER TABLE review_request ADD COLUMN rate_limit_count INTEGER NOT NULL DEFAULT 0 CHECK (rate_limit_count BETWEEN 0 AND 6);
ALTER TABLE review_request ADD COLUMN rate_limited_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE review_request ADD CONSTRAINT review_request_rate_limit_time CHECK (rate_limit_count = 0 OR rate_limited_at IS NOT NULL);

-- Two fixed guards bound concurrent upserts without retaining remote URLs or credentials.
CREATE TABLE integration_cooldown_guard (
    service VARCHAR(4) PRIMARY KEY CHECK (service IN ('GIT', 'AI'))
);
INSERT INTO integration_cooldown_guard(service) VALUES ('GIT'), ('AI');

CREATE TABLE integration_cooldown (
    service VARCHAR(4) NOT NULL REFERENCES integration_cooldown_guard(service),
    origin_hash VARCHAR(64) NOT NULL CHECK (origin_hash ~ '^[0-9a-f]{64}$'),
    retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(service, origin_hash)
);
CREATE INDEX integration_cooldown_expiry_idx ON integration_cooldown(service, retry_at);
