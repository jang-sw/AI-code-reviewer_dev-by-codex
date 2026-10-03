-- Fixed rows serialize each limiter scope without creating guards for arbitrary input.
CREATE TABLE auth_attempt_policy (
    scope VARCHAR(8) PRIMARY KEY CHECK (scope IN ('LOGIN', 'SIGNUP')),
    policy_fingerprint VARCHAR(64) CHECK (policy_fingerprint IS NULL OR policy_fingerprint ~ '^[0-9a-f]{64}$')
);

INSERT INTO auth_attempt_policy(scope, policy_fingerprint) VALUES ('LOGIN', NULL), ('SIGNUP', NULL);

CREATE TABLE auth_attempt_bucket (
    scope VARCHAR(8) NOT NULL REFERENCES auth_attempt_policy(scope),
    key_hash VARCHAR(72) NOT NULL CHECK (key_hash ~ '^(account:|ip:)[0-9a-f]{64}$'),
    attempt_count INTEGER NOT NULL CHECK (attempt_count > 0),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (scope, key_hash)
);

CREATE INDEX auth_attempt_bucket_expiry_idx ON auth_attempt_bucket(scope, expires_at);
