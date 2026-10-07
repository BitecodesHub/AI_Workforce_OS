-- Something worth knowing about a document that was indexed, without stopping it being indexed.
--
-- The first use is a long file cut at the extraction limit: "Only the first 5,000,000 characters
-- (about 1,667 pages) were indexed." That is deliberately not a skip_reason. A skip reason means
-- nothing of the document was indexed, and the ingestion treats any document carrying one as
-- skipped, so writing a truncation there would throw away the part that was read.

ALTER TABLE documents ADD COLUMN notice TEXT;
