-- User ids can be UUIDs (36 chars); the API allows up to 64.
ALTER TABLE payments MODIFY COLUMN participant_id VARCHAR(64) NOT NULL;
