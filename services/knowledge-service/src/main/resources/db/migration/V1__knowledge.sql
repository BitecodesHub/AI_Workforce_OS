-- The knowledge base: where documents come from, how they were cut up, and what supports an answer.
--
-- The vectors live in Qdrant. Postgres holds everything needed to make an answer accountable -
-- which document a passage came from, which page, which revision - because a citation that
-- resolves to nothing is worse than no citation at all.

CREATE TABLE sources (
    id            UUID PRIMARY KEY,
    org_id        UUID        NOT NULL,
    kind          TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    -- A folder id, a repository, a Notion database. Meaning depends on the kind.
    external_ref  TEXT,
    credential_ref TEXT,
    status        TEXT        NOT NULL DEFAULT 'idle',
    -- Where the last successful crawl stopped. Incremental ingestion resumes from here rather
    -- than re-reading a corpus that has not changed.
    watermark     TEXT,
    chunk_size    INT         NOT NULL DEFAULT 1200,
    chunk_overlap INT         NOT NULL DEFAULT 150,
    embedding_provider TEXT   NOT NULL DEFAULT 'sandbox',
    embedding_model    TEXT   NOT NULL DEFAULT 'sandbox-embed-1',
    -- Recorded so a model change is detected rather than producing a silently broken index:
    -- vectors of different dimensions cannot be compared, and the failure looks like poor recall.
    embedding_dimension INT   NOT NULL DEFAULT 1536,
    collection    TEXT        NOT NULL,
    document_count INT        NOT NULL DEFAULT 0,
    chunk_count   INT         NOT NULL DEFAULT 0,
    last_ingested_at TIMESTAMPTZ,
    last_error    TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT,
    updated_by    TEXT,
    CONSTRAINT sources_kind_valid CHECK (kind IN
        ('upload', 'google_drive', 'notion', 'confluence', 'github_wiki')),
    CONSTRAINT sources_status_valid CHECK (status IN
        ('idle', 'ingesting', 'ready', 'failed', 'reconnect_required'))
);

CREATE UNIQUE INDEX sources_org_name_unique ON sources (org_id, lower(name));
CREATE INDEX sources_org_status_idx ON sources (org_id, status);

CREATE TABLE documents (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    source_id    UUID        NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    external_id  TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    uri          TEXT,
    media_type   TEXT        NOT NULL DEFAULT 'text/plain',
    -- Re-ingestion compares this hash and skips a document that has not changed, which turns a
    -- nightly crawl of ten thousand files into a crawl of the dozen that moved.
    content_hash TEXT        NOT NULL,
    byte_size    BIGINT      NOT NULL DEFAULT 0,
    page_count   INT,
    chunk_count  INT         NOT NULL DEFAULT 0,
    status       TEXT        NOT NULL DEFAULT 'indexed',
    -- Why a document could not be indexed, in words a person can act on: an encrypted PDF and a
    -- scanned one need different remedies.
    skip_reason  TEXT,
    indexed_at   TIMESTAMPTZ,
    -- Kept after deletion at the source so retrieval stops returning it immediately, before the
    -- vector purge has run.
    tombstoned_at TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT documents_status_valid CHECK (status IN
        ('indexed', 'pending', 'skipped', 'failed', 'tombstoned'))
);

CREATE UNIQUE INDEX documents_source_external_unique ON documents (source_id, external_id);
CREATE INDEX documents_org_idx ON documents (org_id, status);

CREATE TABLE chunks (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    document_id  UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    source_id    UUID        NOT NULL,
    position     INT         NOT NULL,
    -- The text is kept here as well as in the vector store. A citation has to be able to show
    -- the passage it refers to, and a vector database is not a document store.
    content      TEXT        NOT NULL,
    token_estimate INT       NOT NULL DEFAULT 0,
    page_number  INT,
    char_start   INT,
    char_end     INT,
    heading      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX chunks_document_position_unique ON chunks (document_id, position);
CREATE INDEX chunks_org_idx ON chunks (org_id);
-- Lexical half of hybrid retrieval. Dense search alone misses exact terms - a product code, a
-- person's surname - that a person searching expects to match literally.
CREATE INDEX chunks_content_search_idx ON chunks USING gin (to_tsvector('english', content));

CREATE TABLE ingestion_runs (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    source_id    UUID        NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    status       TEXT        NOT NULL DEFAULT 'running',
    documents_seen    INT    NOT NULL DEFAULT 0,
    documents_indexed INT    NOT NULL DEFAULT 0,
    documents_skipped INT    NOT NULL DEFAULT 0,
    documents_failed  INT    NOT NULL DEFAULT 0,
    chunks_written    INT    NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    failure_reason TEXT,
    CONSTRAINT ingestion_status_valid CHECK (status IN ('running', 'completed', 'failed', 'cancelled'))
);

CREATE INDEX ingestion_runs_source_idx ON ingestion_runs (source_id, started_at DESC);

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX kb_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX kb_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
