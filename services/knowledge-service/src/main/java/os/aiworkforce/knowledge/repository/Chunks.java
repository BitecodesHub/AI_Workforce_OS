package os.aiworkforce.knowledge.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import os.aiworkforce.knowledge.domain.Chunk;
import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Chunks extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByPosition(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    /**
     * The lexical half of hybrid retrieval, using the full-text index from the migration.
     *
     * <p>Terms are combined with OR, not AND, and that is the whole point. Postgres's
     * {@code plainto_tsquery} requires every word to appear in the same passage, so a question
     * phrased naturally - "approval queue human review sensitive actions" - matched nothing at
     * all even though the corpus discusses exactly that. The symptom is the worst kind: search
     * returns an empty result and looks like an empty index rather than a wrong query.
     *
     * <p>Ranking by {@code ts_rank} keeps both halves right: a passage containing five of six
     * terms outranks one containing two, and a passage containing one still appears rather than
     * being discarded.
     *
     * <p>The query is lexicalised by Postgres before being reassembled, so what reaches
     * {@code to_tsquery} is normalised tokens rather than raw user input.
     *
     * <p>A relevance floor keeps OR from becoming indiscriminate. Without it a question about
     * zebra migration matches a passage about database migration, because one common term is
     * enough. Measured against this corpus, passages that genuinely answer a question rank
     * between 0.037 and 0.056, while an incidental single-term match ranks 0.015 - so the floor
     * sits between them. It is a parameter rather than a literal because the right value depends
     * on the corpus, and a number nobody can change is a number nobody can correct.
     *
     * <p>Tombstoned documents are excluded here, which is what makes a deletion take effect
     * immediately rather than when the vector purge catches up.
     */
    @Query(value = """
            with terms as (
                select string_agg(quote_literal(lexeme), ' | ') as query
                from unnest(to_tsvector('english', :query))
            )
            select c.id from chunks c
            join documents d on d.id = c.document_id
            cross join terms
            where c.org_id = :orgId
              and d.tombstoned_at is null
              and terms.query is not null
              and to_tsvector('english', c.content) @@ to_tsquery('english', terms.query)
              and ts_rank(to_tsvector('english', c.content), to_tsquery('english', terms.query))
                  >= :minimumRank
            order by ts_rank(to_tsvector('english', c.content), to_tsquery('english', terms.query)) desc
            """, nativeQuery = true)
    List<UUID> searchLexical(
            @Param("orgId") UUID orgId,
            @Param("query") String query,
            @Param("minimumRank") double minimumRank,
            Pageable pageable);

    @Query(value = """
            select c.id as chunkId, c.document_id as documentId, c.source_id as sourceId,
                   d.title as documentTitle, d.uri as uri, c.page_number as pageNumber,
                   c.heading as heading, c.content as content
            from chunks c
            join documents d on d.id = c.document_id
            where c.org_id = :orgId and c.id in :ids and d.tombstoned_at is null
            """, nativeQuery = true)
    List<CitableChunk> findCitable(@Param("orgId") UUID orgId, @Param("ids") List<UUID> ids);
}
