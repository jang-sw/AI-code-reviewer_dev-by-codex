-- Preserve existing evidence while extending the unnamed V10 reason CHECK portably.
ALTER TABLE manual_review_file RENAME COLUMN reason_code TO legacy_reason_code;
ALTER TABLE manual_review_file ADD COLUMN reason_code VARCHAR(32) NOT NULL DEFAULT 'SOURCE_DIFF_UNAVAILABLE'
    CONSTRAINT manual_review_file_reason_allowed CHECK (reason_code IN ('SOURCE_DIFF_UNAVAILABLE', 'GIT_DIFF_BUDGET', 'AI_INPUT_LIMIT', 'METADATA_CHANGE'));
UPDATE manual_review_file SET reason_code = legacy_reason_code;
ALTER TABLE manual_review_file DROP COLUMN legacy_reason_code;
ALTER TABLE manual_review_file ALTER COLUMN reason_code DROP DEFAULT;
