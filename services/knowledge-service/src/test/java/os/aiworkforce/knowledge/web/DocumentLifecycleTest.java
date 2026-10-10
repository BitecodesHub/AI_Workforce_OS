// @find: tests for document lifecycle, upload, re-upload, replace document, keep both, update document, delete document, delete source, reindex, source create, knowledge base end to end, citations
// @what: Walks a document through the API: uploaded, replaced by an edited copy, kept beside the old one, reindexed and deleted.
package os.aiworkforce.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.knowledge.domain.Document;
import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.Documents;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.knowledge.service.Chunker;
import os.aiworkforce.knowledge.service.EmbeddingService;
import os.aiworkforce.knowledge.service.IngestionService;
import os.aiworkforce.knowledge.service.QdrantClient;
import os.aiworkforce.knowledge.service.RetrievalService;
import os.aiworkforce.knowledge.service.TextExtractor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * A document's life through the API, against the real schema: uploaded, replaced by an edited
 * version, kept beside a namesake, and erased.
 *
 * <p>Replacing a changed document used to fail on the unique (document, position) index, because
 * the old passages were queued for deletion and Hibernate inserts before it deletes. Only a real
 * Postgres shows that, so this runs against one. The vector store and the embedding call are
 * mocked: keyword search, which runs in Postgres, is what proves a passage is or is not findable.
 *
 * <p>Each test runs outside a test-managed transaction so the service's own short transactions
 * commit as they do in production. Needs Docker, and is skipped without it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditingConfig.class)
class DocumentLifecycleTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // The native search names its tables without a schema, as it does in production, where
        // the connection URL selects the schema.
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=knowledge");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String LEAVE = "Leave policy.txt";

    /** Several passages, every one about zeppelins. */
    private static final String V1 = paragraphs("Staff may take zeppelin trips during annual leave", 6);

    /** One passage, about airships, and nothing about zeppelins. */
    private static final String V2 = "Staff may take airship trips during annual leave. Requests go to the dock office.";

    @Autowired
    Sources sources;

    @Autowired
    Documents documents;

    @Autowired
    Chunks chunks;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final UUID org = UUID.randomUUID();
    private EmbeddingService embeddings;
    private QdrantClient vectors;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(any(), anyString(), anyString(), anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(3);
            List<float[]> vectorsOut = new ArrayList<>();
            texts.forEach(text -> vectorsOut.add(new float[] {0.1f, 0.2f}));
            return vectorsOut;
        });
        vectors = mock(QdrantClient.class);
        mvc = mvcFor(ingestion(new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS), new Chunker()));
        signedIn(
                Permission.Codes.KNOWLEDGE_SOURCE_MANAGE,
                Permission.Codes.KNOWLEDGE_READ,
                Permission.Codes.KNOWLEDGE_QUERY);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private IngestionService ingestion(TextExtractor extractor, Chunker chunker) {
        return new IngestionService(
                sources, documents, chunks, extractor, chunker, embeddings, vectors, transactionManager);
    }

    private MockMvc mvcFor(IngestionService ingestion) {
        RetrievalService retrieval = new RetrievalService(vectors, embeddings, chunks, sources);
        return MockMvcBuilders.standaloneSetup(new KnowledgeController(sources, documents, chunks, retrieval, ingestion))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void signedIn(String... permissions) {
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), org.toString(), "role", Set.of(permissions), 0L));
    }

    private static String paragraphs(String sentence, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("Section ").append(i).append(". ");
            text.append((sentence + ", subject to approval by a manager. ").repeat(8)).append("\n\n");
        }
        return text.toString();
    }

    /** A source on a real embedding model, so its passages go to the (mocked) vector store. */
    private UUID createMeaningSource(String name) throws Exception {
        UUID id = createSource(name);
        sources.findById(id).ifPresent(source -> {
            source.setEmbeddingProvider("openai");
            source.setEmbeddingModel("text-embedding-3-small");
            sources.save(source);
        });
        return id;
    }

    /** A source as one is created today: on the offline sandbox's embeddings. */
    private UUID createSource(String name) throws Exception {
        String body = mvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions upload(MockMvc client, UUID sourceId, String name, String text, String mode)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/sources/{id}/documents", sourceId)
                .file(new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
        if (mode != null) {
            request.param("mode", mode);
        }
        return client.perform(request);
    }

    private ResultActions upload(UUID sourceId, String name, String text) throws Exception {
        return upload(mvc, sourceId, name, text, null);
    }

    private static UUID documentId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.documentId"));
    }

    private List<String> titlesFound(String query) throws Exception {
        String body = mvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + query + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.passages[*].documentTitle");
    }

    /** The same ids in any order: the passages of a document have no order of their own in a query. */
    private static List<UUID> sameIds(List<UUID> expected) {
        return argThat(ids -> ids != null && ids.size() == expected.size() && Set.copyOf(ids).equals(Set.copyOf(expected)));
    }

    private ResultActions source(UUID sourceId) throws Exception {
        return mvc.perform(get("/api/sources/{id}", sourceId));
    }

    @Test
    @DisplayName("an edited file uploaded under the same name replaces the old version, which stops being found")
    void changedReuploadReplaces() throws Exception {
        UUID sourceId = createSource("Policies");
        ResultActions first = upload(sourceId, LEAVE, V1).andExpect(status().isOk());
        first.andExpect(jsonPath("$.status").value("indexed"));
        UUID documentId = documentId(first);
        List<UUID> v1Passages = chunks.findIdsByDocumentId(documentId);
        assertThat(v1Passages).hasSizeGreaterThan(1);
        assertThat(titlesFound("zeppelin")).contains(LEAVE);

        upload(sourceId, LEAVE, V2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("replaced"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.detail").value(startsWith("Replaced the version indexed on ")))
                .andExpect(jsonPath("$.replacedIndexedAt").exists())
                .andExpect(jsonPath("$.vectorWarning").doesNotExist());

        assertThat(chunks.findIdsByDocumentId(documentId)).hasSize(1).doesNotContainAnyElementsOf(v1Passages);
        assertThat(titlesFound("zeppelin")).isEmpty();
        assertThat(titlesFound("airship")).containsExactly(LEAVE);
        source(sourceId).andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.chunkCount").value(1));
        mvc.perform(get("/api/sources/{id}/documents", sourceId))
                .andExpect(jsonPath("$[0].status").value("indexed"))
                .andExpect(jsonPath("$[0].chunkCount").value(1));

        // The old version's vectors are retired by id once the new ones are written.
        String collection = sources.findById(sourceId).orElseThrow().getCollection();
        verify(vectors).deletePoints(eq(collection), sameIds(v1Passages));
    }

    @Test
    @DisplayName("the same bytes again are reported unchanged and nothing is rewritten")
    void identicalReuploadIsUnchanged() throws Exception {
        UUID sourceId = createSource("Policies");
        upload(sourceId, LEAVE, V2).andExpect(status().isOk());

        upload(sourceId, LEAVE, V2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"))
                .andExpect(jsonPath("$.replacedIndexedAt").doesNotExist());
    }

    @Test
    @DisplayName("keep both stores the new file beside the old one under a numbered name")
    void keepBothCreatesASecondDocument() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID first = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));

        ResultActions second = upload(mvc, sourceId, LEAVE, V2, "keep_both")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.title").value("Leave policy (2).txt"));

        assertThat(documentId(second)).isNotEqualTo(first);
        assertThat(titlesFound("zeppelin")).contains(LEAVE);
        assertThat(titlesFound("airship")).containsExactly("Leave policy (2).txt");
        source(sourceId).andExpect(jsonPath("$.documentCount").value(2));

        upload(mvc, sourceId, LEAVE, "A third take on airship travel.", "keep_both")
                .andExpect(jsonPath("$.title").value("Leave policy (3).txt"));
    }

    @Test
    @DisplayName("an unknown mode is refused before anything is read")
    void unknownMode() throws Exception {
        UUID sourceId = createSource("Policies");

        upload(mvc, sourceId, LEAVE, V2, "merge").andExpect(status().isUnprocessableEntity());
        assertThat(documents.findBySourceIdOrderByTitle(sourceId)).isEmpty();
    }

    @Test
    @DisplayName("deleting a document erases it from search, from the counts and from the vector store")
    void deleteDocumentErases() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));
        upload(sourceId, "Travel.txt", V2).andExpect(status().isOk());
        source(sourceId).andExpect(jsonPath("$.documentCount").value(2));

        mvc.perform(delete("/api/sources/{s}/documents/{d}", sourceId, leave)).andExpect(status().isNoContent());

        assertThat(titlesFound("zeppelin")).isEmpty();
        assertThat(titlesFound("airship")).containsExactly("Travel.txt");
        assertThat(documents.findById(leave)).isEmpty();
        assertThat(chunks.findIdsByDocumentId(leave)).isEmpty();
        source(sourceId).andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.chunkCount").value(1));
        verify(vectors).deleteByDocument(sources.findById(sourceId).orElseThrow().getCollection(), leave);

        mvc.perform(delete("/api/sources/{s}/documents/{d}", sourceId, leave)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a document is only deleted through the source it belongs to")
    void deleteThroughAnotherSourceIsNotFound() throws Exception {
        UUID policies = createSource("Policies");
        UUID other = createSource("Other");
        UUID leave = documentId(upload(policies, LEAVE, V1).andExpect(status().isOk()));

        mvc.perform(delete("/api/sources/{s}/documents/{d}", other, leave)).andExpect(status().isNotFound());

        assertThat(documents.findById(leave)).isPresent();
        verify(vectors, never()).deleteByDocument(anyString(), eq(leave));
    }

    @Test
    @DisplayName("deleting needs knowledge:source_manage")
    void deleteNeedsManage() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));
        signedIn(Permission.Codes.KNOWLEDGE_READ, Permission.Codes.KNOWLEDGE_QUERY);

        mvc.perform(delete("/api/sources/{s}/documents/{d}", sourceId, leave)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/sources/{s}", sourceId)).andExpect(status().isForbidden());

        assertThat(documents.findById(leave)).isPresent();
        assertThat(titlesFound("zeppelin")).contains(LEAVE);
    }

    @Test
    @DisplayName("deleting a source erases its documents and its vectors by filter, leaving other sources alone")
    void deleteSourceErases() throws Exception {
        UUID policies = createSource("Policies");
        UUID travel = createSource("Travel");
        UUID leave = documentId(upload(policies, LEAVE, V1).andExpect(status().isOk()));
        upload(travel, "Travel.txt", V2).andExpect(status().isOk());
        String collection = sources.findById(policies).orElseThrow().getCollection();

        mvc.perform(delete("/api/sources/{s}", policies)).andExpect(status().isNoContent());

        source(policies).andExpect(status().isNotFound());
        assertThat(documents.findById(leave)).isEmpty();
        assertThat(chunks.findIdsByDocumentId(leave)).isEmpty();
        assertThat(titlesFound("zeppelin")).isEmpty();
        assertThat(titlesFound("airship")).containsExactly("Travel.txt");
        verify(vectors).deleteBySource(collection, policies);
        verify(vectors, never()).deleteBySource(anyString(), eq(travel));
    }

    @Test
    @DisplayName("a sandbox source never writes vectors, so a vector store that is down raises no warning")
    void sandboxSourceSkipsTheVectorStore() throws Exception {
        doThrow(new IllegalStateException("connection refused")).when(vectors).upsert(anyString(), anyList());
        UUID sourceId = createSource("Handbook");

        upload(sourceId, "leave.txt", paragraphs("Annual leave is 25 days", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorWarning").doesNotExist());

        verify(vectors, never()).upsert(anyString(), anyList());
        assertThat(sources.findById(sourceId).orElseThrow().getLastError()).isNull();
    }

    @Test
    @DisplayName("with the vector store down a file is still indexed for keyword search, and says so")
    void vectorStoreDown() throws Exception {
        doThrow(new IllegalStateException("connection refused")).when(vectors).upsert(anyString(), anyList());
        UUID sourceId = createMeaningSource("Policies");

        upload(sourceId, LEAVE, V2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.vectorWarning").value(startsWith("Indexed for keyword search")))
                .andExpect(jsonPath("$.detail").value(containsString("vector store was unavailable")));

        assertThat(titlesFound("airship")).containsExactly(LEAVE);
        source(sourceId)
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.lastError").value(startsWith("Vector indexing is unavailable")));
    }

    @Test
    @DisplayName("an embedding model that cannot be used is named as the cause, never the vector store or a URL")
    void embeddingRefusedIsReportedPlainly() throws Exception {
        when(embeddings.embed(any(), anyString(), anyString(), anyList()))
                .thenThrow(new EmbeddingService.EmbeddingRefused(
                        "gemini", "text-embedding-004", "That provider has not been configured for this workspace."));
        UUID sourceId = createMeaningSource("Policies");

        upload(sourceId, LEAVE, V2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.detail").value(containsString("embedding model could not be used")));
        source(sourceId)
                .andExpect(jsonPath("$.lastError").value(containsString("has not been configured")))
                .andExpect(jsonPath("$.lastError").value(not(containsString("http"))));

        mvc.perform(post("/api/sources/{id}/reindex", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorised").value(false))
                .andExpect(jsonPath("$.detail").value(containsString("gemini / text-embedding-004")))
                .andExpect(jsonPath("$.detail").value(not(containsString("vector store"))));
        assertThat(titlesFound("airship")).containsExactly(LEAVE);
    }

    @Test
    @DisplayName("a failure after the vectors are written takes the new points back out")
    void failureAfterUpsertRemovesNewPoints() throws Exception {
        UUID sourceId = createMeaningSource("Policies");
        AtomicReference<List<UUID>> written = new AtomicReference<>();
        doAnswer(call -> {
                    List<QdrantClient.Point> points = call.getArgument(1);
                    written.set(points.stream().map(QdrantClient.Point::chunkId).toList());
                    // Somebody deletes the document while its vectors are being written.
                    documents.deleteById(UUID.fromString((String) points.get(0).payload().get("documentId")));
                    return null;
                })
                .when(vectors)
                .upsert(anyString(), anyList());

        upload(sourceId, LEAVE, V1).andExpect(status().isConflict());

        String collection = sources.findById(sourceId).orElseThrow().getCollection();
        assertThat(written.get()).isNotEmpty();
        verify(vectors).deletePoints(collection, written.get());
        assertThat(titlesFound("zeppelin")).isEmpty();
    }

    @Test
    @DisplayName("a tombstoned document uploaded again becomes findable again")
    void reuploadClearsTombstone() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V2).andExpect(status().isOk()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Document document = documents.findById(leave).orElseThrow();
            document.tombstone();
            document.setSkipReason("Left over from an old crawl.");
        });
        assertThat(titlesFound("airship")).isEmpty();

        upload(sourceId, LEAVE, V2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("indexed"));

        Document document = documents.findById(leave).orElseThrow();
        assertThat(document.getTombstonedAt()).isNull();
        assertThat(document.getSkipReason()).isNull();
        assertThat(titlesFound("airship")).containsExactly(LEAVE);
    }

    @Test
    @DisplayName("a file cut at the extraction limit is indexed, and its notice is kept on the document")
    void truncatedFileCarriesANotice() throws Exception {
        UUID sourceId = createSource("Policies");
        MockMvc capped = mvcFor(ingestion(new TextExtractor(1_000), new Chunker()));

        upload(capped, sourceId, LEAVE, V1, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.notice").value(startsWith(
                        "Only the first 1,000 characters (about 1 page) were indexed.")))
                .andExpect(jsonPath("$.detail").value(startsWith("Only the first 1,000")));

        mvc.perform(get("/api/sources/{id}/documents", sourceId))
                .andExpect(jsonPath("$[0].notice").value(startsWith("Only the first 1,000")))
                .andExpect(jsonPath("$[0].skipReason").doesNotExist());
    }

    @Test
    @DisplayName("a revision that yields no passages leaves the counts at zero rather than at the old version's")
    void emptyRevisionResetsCounts() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));
        List<UUID> v1Passages = chunks.findIdsByDocumentId(leave);
        Chunker nothing = mock(Chunker.class);
        when(nothing.chunk(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        upload(mvcFor(ingestion(new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS), nothing)), sourceId, LEAVE, V2, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"));

        assertThat(documents.findById(leave).orElseThrow().getChunkCount()).isZero();
        source(sourceId).andExpect(jsonPath("$.documentCount").value(0)).andExpect(jsonPath("$.chunkCount").value(0));
        assertThat(titlesFound("zeppelin")).isEmpty();
        verify(vectors).deletePoints(eq(sources.findById(sourceId).orElseThrow().getCollection()), sameIds(v1Passages));
    }

    /**
     * An {@link IngestionService} whose first two looks for a document wait for each other, so two
     * uploads of one name both find it missing before either has inserted it - the race a double
     * drop of a file, or two people uploading a policy, produces.
     */
    private IngestionService racingIngestion() {
        CountDownLatch bothLooked = new CountDownLatch(2);
        AtomicInteger looks = new AtomicInteger();
        Documents racing = mock(Documents.class, AdditionalAnswers.delegatesTo(documents));
        doAnswer(call -> {
                    Optional<Document> found =
                            documents.findBySourceIdAndExternalId(call.getArgument(0), call.getArgument(1));
                    if (looks.incrementAndGet() <= 2) {
                        bothLooked.countDown();
                        bothLooked.await(10, TimeUnit.SECONDS);
                    }
                    return found;
                })
                .when(racing)
                .findBySourceIdAndExternalId(any(), any());
        return new IngestionService(
                sources,
                racing,
                chunks,
                new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS),
                new Chunker(),
                embeddings,
                vectors,
                transactionManager);
    }

    private List<IngestionService.IngestResult> inParallel(
            IngestionService service, UUID sourceId, List<String> texts, IngestionService.UploadMode mode)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(texts.size());
        try {
            List<Future<IngestionService.IngestResult>> pending = new ArrayList<>();
            for (String text : texts) {
                pending.add(pool.submit(() ->
                        service.ingest(org, sourceId, LEAVE, text.getBytes(StandardCharsets.UTF_8), mode)));
            }
            List<IngestionService.IngestResult> results = new ArrayList<>();
            for (Future<IngestionService.IngestResult> one : pending) {
                results.add(one.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the same new file uploaded twice at once is indexed once, and neither upload fails on the unique name")
    void doubleDropOfANewFile() throws Exception {
        UUID sourceId = createSource("Policies");

        List<IngestionService.IngestResult> results =
                inParallel(racingIngestion(), sourceId, List.of(V1, V1), IngestionService.UploadMode.REPLACE);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(IngestionService.IngestResult::documentId).containsOnly(results.get(0).documentId());
        assertThat(results).extracting(IngestionService.IngestResult::status).containsAnyOf("indexed", "replaced");
        assertThat(results)
                .extracting(IngestionService.IngestResult::status)
                .allSatisfy(status -> assertThat(status).isIn("indexed", "replaced", "unchanged"));
        assertThat(documents.findBySourceIdOrderByTitle(sourceId)).hasSize(1).allSatisfy(document -> {
            assertThat(document.getStatus()).isEqualTo("indexed");
            assertThat(document.getChunkCount()).isEqualTo(chunks.findIdsByDocumentId(document.getId()).size());
        });
        source(sourceId).andExpect(jsonPath("$.documentCount").value(1));
        assertThat(titlesFound("zeppelin")).isNotEmpty().containsOnly(LEAVE);
    }

    @Test
    @DisplayName("two different files of one new name at once end as one document: the later replaces, or is told it lost")
    void twoVersionsOfANewFileAtOnce() throws Exception {
        UUID sourceId = createSource("Policies");

        // Each upload ends one of two ways, and never as the unique-name failure: it indexes, or it
        // is told plainly that the other version replaced it while it was being indexed.
        List<Object> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        IngestionService racing = racingIngestion();
        try {
            List<Future<IngestionService.IngestResult>> pending = new ArrayList<>();
            for (String text : List.of(V1, V2)) {
                pending.add(pool.submit(() -> racing.ingest(
                        org, sourceId, LEAVE, text.getBytes(StandardCharsets.UTF_8), IngestionService.UploadMode.REPLACE)));
            }
            for (Future<IngestionService.IngestResult> one : pending) {
                try {
                    outcomes.add(one.get(30, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).hasSize(2);
        for (Object outcome : outcomes) {
            if (outcome instanceof Throwable failure) {
                assertThat(failure.getMessage()).contains("replaced it while it was being indexed");
            } else {
                assertThat(((IngestionService.IngestResult) outcome).status()).isIn("indexed", "replaced");
            }
        }
        assertThat(outcomes).anyMatch(outcome -> outcome instanceof IngestionService.IngestResult);
        assertThat(documents.findBySourceIdOrderByTitle(sourceId)).hasSize(1);
        assertThat(documents.findBySourceIdOrderByTitle(sourceId).get(0).getStatus()).isEqualTo("indexed");
    }

    @Test
    @DisplayName("two uploads of one new name at once, both kept, end as the file and its numbered twin")
    void keepBothRace() throws Exception {
        UUID sourceId = createSource("Policies");

        List<IngestionService.IngestResult> results =
                inParallel(racingIngestion(), sourceId, List.of(V1, V2), IngestionService.UploadMode.KEEP_BOTH);

        assertThat(results).extracting(IngestionService.IngestResult::status).containsOnly("indexed");
        assertThat(results)
                .extracting(IngestionService.IngestResult::title)
                .containsExactlyInAnyOrder(LEAVE, "Leave policy (2).txt");
        assertThat(documents.findBySourceIdOrderByTitle(sourceId)).hasSize(2);
        source(sourceId).andExpect(jsonPath("$.documentCount").value(2));
    }

    /** Leaves a document as a crash between its first and last step would: passages stored, outcome unrecorded. */
    private void leavePending(UUID sourceId, UUID documentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Document document = documents.findById(documentId).orElseThrow();
            document.setStatus("pending");
            document.setIndexedAt(null);
            Source source = sources.findById(sourceId).orElseThrow();
            source.setDocumentCount(0);
            source.setChunkCount(0);
        });
    }

    @Test
    @DisplayName("indexing again finishes a document left pending, which was findable but counted nowhere")
    void reindexRecoversAPendingDocument() throws Exception {
        UUID sourceId = createSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));
        int passages = chunks.findIdsByDocumentId(leave).size();
        leavePending(sourceId, leave);
        source(sourceId).andExpect(jsonPath("$.documentCount").value(0));
        assertThat(titlesFound("zeppelin")).contains(LEAVE);

        mvc.perform(post("/api/sources/{id}/reindex", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsQueued").value(1))
                .andExpect(jsonPath("$.recovered").value(1))
                .andExpect(jsonPath("$.vectorised").value(true));

        Document document = documents.findById(leave).orElseThrow();
        assertThat(document.getStatus()).isEqualTo("indexed");
        assertThat(document.getIndexedAt()).isNotNull();
        source(sourceId)
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.chunkCount").value(passages));
        mvc.perform(get("/api/sources/{id}/documents", sourceId)).andExpect(jsonPath("$[0].status").value("indexed"));

        // A second pass has nothing left to recover.
        mvc.perform(post("/api/sources/{id}/reindex", sourceId)).andExpect(jsonPath("$.recovered").value(0));
    }

    @Test
    @DisplayName("a pending document is finished even when the vector store is still down, and says so")
    void reindexRecoversWithTheVectorStoreDown() throws Exception {
        UUID sourceId = createMeaningSource("Policies");
        UUID leave = documentId(upload(sourceId, LEAVE, V1).andExpect(status().isOk()));
        leavePending(sourceId, leave);
        doThrow(new IllegalStateException("connection refused")).when(vectors).upsert(anyString(), anyList());

        mvc.perform(post("/api/sources/{id}/reindex", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovered").value(1))
                .andExpect(jsonPath("$.vectorised").value(false))
                .andExpect(jsonPath("$.detail").value(containsString("still unavailable")));

        assertThat(documents.findById(leave).orElseThrow().getStatus()).isEqualTo("indexed");
        source(sourceId)
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.lastError").value(startsWith("Vector indexing is unavailable")));
        assertThat(titlesFound("zeppelin")).contains(LEAVE);
    }
}
