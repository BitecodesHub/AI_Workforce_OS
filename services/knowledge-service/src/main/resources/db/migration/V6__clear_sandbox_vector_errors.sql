-- A source on the offline sandbox's embeddings is searched by keyword only; its vectors were never
-- read. Until 8 Oct 2026 they were still written, so a vector store that was down left these
-- sources saying search by meaning had failed. They no longer write vectors, and the stale
-- message is cleared here.
UPDATE sources
SET last_error = NULL
WHERE lower(embedding_provider) = 'sandbox'
  AND last_error LIKE 'Vector indexing is unavailable:%';
