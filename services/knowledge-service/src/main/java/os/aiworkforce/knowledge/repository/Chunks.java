package os.aiworkforce.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.knowledge.domain.Chunk;

/*
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all - the scan reports "Found 0 JPA repository interfaces" and the
 * application fails at startup on a missing bean, which is a considerably more confusing symptom
 * than the cause deserves. One interface per file is also the convention.
 */

public interface Chunks extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByPosition(UUID documentId);

    /**
     * The identifiers of a document's passages, which are also its vector point ids.
     *
     * <p>Read before the passages are replaced, so their vectors can be removed once the new ones
     * are in place - without loading every passage's text just to learn its id.
     */
    @Query("select c.id from Chunk c where c.documentId = :documentId")
    List<UUID> findIdsByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Removes a document's passages in one statement, at once.
     *
     * <p>A bulk delete, not a derived one. A derived delete loads every row and queues a removal
     * for each, and Hibernate runs queued inserts before queued deletes - so replacing a changed
     * document inserted its new passages while the old ones, holding the same positions, were
     * still there, and the unique index on (document, position) refused the whole upload. This runs
     * immediately, after flushing anything pending. It leaves the persistence context alone, so the
     * document being re-indexed stays attached.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from Chunk c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    /** Every passage of a source, for erasing the whole source. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from Chunk c where c.sourceId = :sourceId")
    int deleteBySourceId(@Param("sourceId") UUID sourceId);

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
     * <p>Those tokens are already stemmed, so they are read back with the {@code simple}
     * configuration, which stems nothing. Reading them with {@code english} stemmed them a second
     * time, and a stem is not always its own stem: "remuneration" became "remuner" and then "remun",
     * which no passage contains, so a document that said "remuneration" in every line could not be
     * found by asking about remuneration.
     *
     * <p>A relevance floor keeps OR from becoming indiscriminate. Without it a question about
     * zebra migration matches a passage about database migration, because one common term is
     * enough. Measured against this corpus, passages that genuinely answer a question rank
     * between 0.037 and 0.056, while an incidental single-term match ranks 0.015 - so the floor
     * sits between them. It is a parameter rather than a literal because the right value depends
     * on the corpus, and a number nobody can change is a number nobody can correct.
     *
     * <p>The floor eases as the query grows. Each OR term a passage lacks lowers its rank, so a fixed
     * floor turned away the right passage for a longer question: "SIH project tech stack use"
     * ranked the matching slide 0.027 against 0.03. Up to two terms the floor is {@code minimumRank};
     * beyond that it falls in proportion, but never below {@code lowestRank}, which still sits above
     * the 0.015 of an incidental one-term match.
     *
     * <p>Tombstoned documents are excluded here as well. A document deleted by a person is erased
     * outright, passages and all, so this only matters for a row still carrying a tombstone.
     *
     * <p>{@code allowed} limits the search to the sources the caller may read, as a Postgres array
     * literal (see {@link #uuidArray}). It is applied here, before the limit, never to the results
     * afterwards: filtering after the limit returned two passages out of a requested ten, or none,
     * whenever the best matches sat in a source the caller had not asked for or may not see.
     */
    @Query(
            value =
                    """
            with terms as (
                select string_agg(quote_literal(lexeme), ' | ') as query, count(*) as n
                from unnest(to_tsvector('english', :query))
            )
            select c.id from chunks c
            join documents d on d.id = c.document_id
            cross join terms
            where c.org_id = :orgId
              and c.source_id = any(cast(:allowed as uuid[]))
              and d.tombstoned_at is null
              and terms.query is not null
              and to_tsvector('english', c.content) @@ to_tsquery('simple', terms.query)
              and ts_rank(to_tsvector('english', c.content), to_tsquery('simple', terms.query))
                  >= greatest(:minimumRank * least(1.0, 2.0 / terms.n), :lowestRank)
            order by ts_rank(to_tsvector('english', c.content), to_tsquery('simple', terms.query)) desc
            """,
            nativeQuery = true)
    List<UUID> searchLexical(
            @Param("orgId") UUID orgId,
            @Param("query") String query,
            @Param("allowed") String allowed,
            @Param("minimumRank") double minimumRank,
            @Param("lowestRank") double lowestRank,
            Pageable pageable);

    /**
     * The passages behind a list of hits, with their provenance.
     *
     * <p>Filtered by the same allowed sources as the searches. A vector hit is resolved here, so
     * this is the last place a passage from a source the caller may not read could slip through.
     */
    @Query(
            value =
                    """
            select c.id as chunkId, c.document_id as documentId, c.source_id as sourceId,
                   d.title as documentTitle, d.uri as uri, c.page_number as pageNumber,
                   c.heading as heading, c.content as content
            from chunks c
            join documents d on d.id = c.document_id
            where c.org_id = :orgId
              and c.id in :ids
              and c.source_id = any(cast(:allowed as uuid[]))
              and d.tombstoned_at is null
            """,
            nativeQuery = true)
    List<CitableChunk> findCitable(
            @Param("orgId") UUID orgId, @Param("ids") List<UUID> ids, @Param("allowed") String allowed);

    /** One page of a document's passages in reading order, for showing what a document was cut into. */
    Page<Chunk> findPageByDocumentIdOrderByPosition(UUID documentId, Pageable pageable);

    /**
     * A set of ids as a Postgres array literal, {@code {id,id}}, for {@code cast(:p as uuid[])}.
     *
     * <p>Passed as text rather than bound as an array, because how a JDBC driver and Hibernate bind
     * a Java array to a native query differs between versions, while a literal cast is plain SQL. A
     * UUID's own text form cannot contain a brace or a comma, so nothing here needs escaping.
     */
    static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
