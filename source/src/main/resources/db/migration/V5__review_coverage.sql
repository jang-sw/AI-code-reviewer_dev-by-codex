ALTER TABLE reviewed_commit ADD COLUMN coverage_type VARCHAR(20) NOT NULL DEFAULT 'FULL'
    CHECK (coverage_type IN ('FULL', 'EMPTY', 'METADATA_ONLY'));
ALTER TABLE reviewed_commit ADD COLUMN coverage_details TEXT NOT NULL DEFAULT ''
    CHECK (CHAR_LENGTH(coverage_details) <= 16000);
