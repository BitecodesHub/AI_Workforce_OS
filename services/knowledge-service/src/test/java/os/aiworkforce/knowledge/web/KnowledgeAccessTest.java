package os.aiworkforce.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Who may see which source, what a search is limited to before it ranks anything, and how a
 * document's passages are opened, against the real schema.
 *
 * <p>Filtering has to happen inside the search. Only a real Postgres shows the difference between
 * that and filtering the results afterwards, which returned a handful of passages out of the ten
 * asked for whenever the best matches sat in a source the caller had not asked for or may not see.
 * The vector store and the embedding call are mocked, because the keyword half runs in Postgres.
 *
 * <p>Needs Docker, and is skipped without it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaAuditingConfig.class)
class KnowledgeAccessTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=knowledge");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Somebody who can search and read, and nothing more: the employee's share of knowledge. */
    private static final String[] EMPLOYEE = {Permission.Codes.KNOWLEDGE_READ, Permission.Codes.KNOWLEDGE_QUERY};

    private static final String[] MANAGER = {
        Permission.Codes.KNOWLEDGE_READ, Permission.Codes.KNOWLEDGE_QUERY, Permission.Codes.KNOWLEDGE_SOURCE_MANAGE
    };

    private static final String SALARY = "Salary bands for level four engineers. Remuneration is reviewed each April.";
    private static final String LEAVE = "Annual leave is twenty five days a year. Leave requests go to a manager.";

    @Autowired
    Sources sources;

    @Autowired
    Documents documents;

    @Autowired
    Chunks chunks;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** A workspace of its own for each test, so what one uploads is invisible to the next. */
    private final UUID org = UUID.randomUUID();

    private EmbeddingService embeddings;
    private QdrantClient vectors;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(any(), anyString(), anyString(), anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(3);
            List<float[]> out = new ArrayList<>();
            texts.forEach(text -> out.add(new float[] {0.1f, 0.2f}));
            return out;
        });
        vectors = mock(QdrantClient.class);
        IngestionService ingestion = new IngestionService(
                sources,
                documents,
                chunks,
                new TextExtractor(TextExtractor.DEFAULT_MAX_CHARS),
                new Chunker(),
                embeddings,
                vectors,
                transactionManager);
        RetrievalService retrieval = new RetrievalService(vectors, embeddings, chunks, sources);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(sources, documents, chunks, retrieval, ingestion))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        signedIn(org, MANAGER);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static void signedIn(UUID workspace, String... permissions) {
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), workspace.toString(), "role", Set.of(permissions), 0L));
    }

    private UUID createSource(String name, boolean restricted) throws Exception {
        String body = mvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"restricted\":" + restricted + "}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID upload(UUID sourceId, String name, String text) throws Exception {
        String body = mvc.perform(multipart("/api/sources/{id}/documents", sourceId)
                        .file(new MockMultipartFile(
                                "file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.documentId"));
    }

    private ResultActions search(String query, Integer limit, UUID... sourceIds) throws Exception {
        StringBuilder body = new StringBuilder("{\"query\":\"" + query + "\"");
        if (limit != null) {
            body.append(",\"limit\":").append(limit);
        }
        if (sourceIds.length > 0) {
            body.append(",\"sourceIds\":[");
            for (int i = 0; i < sourceIds.length; i++) {
                body.append(i == 0 ? "" : ",").append('"').append(sourceIds[i]).append('"');
            }
            body.append("]");
        }
        return mvc.perform(post("/api/knowledge/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.append("}").toString()));
    }

    private void setProvider(UUID sourceId, String provider) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Source source = sources.findById(sourceId).orElseThrow();
            source.setEmbeddingProvider(provider);
            source.setEmbeddingModel(provider + "-embed");
        });
    }

    /* ---- Restricted sources ------------------------------------------------------------------ */

    @Test
    @DisplayName("a restricted source is invisible to an employee in every read, and visible to a manager")
    void restrictedSourceIsInvisibleToAnEmployee() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID hr = createSource("HR", true);
        UUID leave = upload(policies, "Leave.txt", LEAVE);
        UUID salary = upload(hr, "Salary bands.txt", SALARY);

        signedIn(org, EMPLOYEE);

        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Policies")));
        mvc.perform(get("/api/sources/{id}", hr)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/{id}/documents", hr)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", hr, salary)).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/health").param("includeCounts", "true"))
                .andExpect(jsonPath("$.sources").value(1))
                .andExpect(jsonPath("$.ready").value(1))
                .andExpect(jsonPath("$.documents").value(1))
                .andExpect(jsonPath("$.passages").value(1));
        // Not found by search, with the source named or not.
        search("remuneration", null).andExpect(jsonPath("$.grounded").value(false));
        search("remuneration", null, hr).andExpect(jsonPath("$.passages", hasSize(0)));
        search("remuneration", null, hr, policies).andExpect(jsonPath("$.passages", hasSize(0)));
        // What they may read is unaffected.
        search("leave", null).andExpect(jsonPath("$.passages[0].documentId").value(leave.toString()));

        signedIn(org, MANAGER);

        mvc.perform(get("/api/sources"))
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder("Policies", "HR")))
                .andExpect(jsonPath("$[?(@.name=='HR')].restricted").value(contains(true)))
                .andExpect(jsonPath("$[?(@.name=='Policies')].restricted").value(contains(false)));
        mvc.perform(get("/api/sources/{id}", hr)).andExpect(status().isOk());
        mvc.perform(get("/api/sources/{id}/documents", hr)).andExpect(jsonPath("$[0].title").value("Salary bands.txt"));
        mvc.perform(get("/api/knowledge/health").param("includeCounts", "true"))
                .andExpect(jsonPath("$.sources").value(2))
                .andExpect(jsonPath("$.documents").value(2));
        search("remuneration", null)
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.passages[0].documentId").value(salary.toString()))
                .andExpect(jsonPath("$.passages[0].sourceId").value(hr.toString()));
    }

    @Test
    @DisplayName("a restricted source's matches do not crowd out what the caller may read")
    void restrictedMatchesDoNotCrowdOutOthers() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID hr = createSource("HR", true);
        for (int i = 0; i < 12; i++) {
            upload(policies, "Travel " + i + ".txt", "Zeppelin travel rule number " + i + " applies to staff.");
        }
        // Thirty-five far stronger matches, more than the keyword search ever asks Postgres for.
        for (int i = 0; i < 35; i++) {
            upload(hr, "Case " + i + ".txt", "Zeppelin zeppelin zeppelin zeppelin zeppelin zeppelin case " + i + ".");
        }

        signedIn(org, EMPLOYEE);

        search("zeppelin", 10)
                .andExpect(jsonPath("$.passages", hasSize(10)))
                .andExpect(jsonPath("$.passages[*].sourceId", everyItem(is(policies.toString()))));
    }

    @Test
    @DisplayName("a source can be restricted at creation, so it is never open to the workspace first")
    void restrictedAtCreation() throws Exception {
        UUID hr = createSource("HR", true);

        mvc.perform(get("/api/sources/{id}", hr)).andExpect(jsonPath("$.restricted").value(true));
        UUID open = createSource("Policies", false);
        mvc.perform(get("/api/sources/{id}", open)).andExpect(jsonPath("$.restricted").value(false));
        // Left out, the field means workspace-wide, as it did before the field existed.
        String body = mvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Handbook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Handbook");
    }

    @Test
    @DisplayName("restricting a source takes effect on the very next search, and lifting it restores it")
    void restrictingTakesEffectAtOnce() throws Exception {
        UUID policies = createSource("Policies", false);
        upload(policies, "Salary bands.txt", SALARY);
        signedIn(org, EMPLOYEE);
        search("remuneration", null).andExpect(jsonPath("$.passages", hasSize(1)));

        signedIn(org, MANAGER);
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restricted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.name").value("Policies"));

        signedIn(org, EMPLOYEE);
        search("remuneration", null).andExpect(jsonPath("$.passages", hasSize(0)));
        mvc.perform(get("/api/sources/{id}", policies)).andExpect(status().isNotFound());

        signedIn(org, MANAGER);
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restricted\":false}"))
                .andExpect(jsonPath("$.restricted").value(false));

        signedIn(org, EMPLOYEE);
        search("remuneration", null).andExpect(jsonPath("$.passages", hasSize(1)));
    }

    @Test
    @DisplayName("renaming and restricting need knowledge:source_manage, and refuse a taken or empty name")
    void updateNeedsManage() throws Exception {
        UUID policies = createSource("Policies", false);
        createSource("Travel", false);

        signedIn(org, EMPLOYEE);
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restricted\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine now\"}"))
                .andExpect(status().isForbidden());
        assertThat(sources.findById(policies).orElseThrow().isRestricted()).isFalse();

        signedIn(org, MANAGER);
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  People policies  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("People policies"))
                .andExpect(jsonPath("$.restricted").value(false));
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"travel\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/sources/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restricted\":true}"))
                .andExpect(status().isNotFound());
        // An empty change keeps everything as it was.
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("People policies"));
    }

    @Test
    @DisplayName("a source in another workspace is never found, restricted or not")
    void otherWorkspaceSeesNothing() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID leave = upload(policies, "Leave.txt", LEAVE);

        signedIn(UUID.randomUUID(), MANAGER);

        mvc.perform(get("/api/sources")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/sources/{id}", policies)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, leave)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/sources/{id}", policies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restricted\":true}"))
                .andExpect(status().isNotFound());
        search("leave", null).andExpect(jsonPath("$.passages", hasSize(0)));
    }

    /* ---- Filters inside the search ----------------------------------------------------------- */

    @Test
    @DisplayName("a search limited to one source returns a full limit, though the best matches sit in another")
    void filteredSearchReturnsAFullLimit() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID cases = createSource("Cases", false);
        for (int i = 0; i < 12; i++) {
            upload(policies, "Travel " + i + ".txt", "Zeppelin travel rule number " + i + " applies to staff.");
        }
        for (int i = 0; i < 35; i++) {
            upload(cases, "Case " + i + ".txt", "Zeppelin zeppelin zeppelin zeppelin zeppelin zeppelin case " + i + ".");
        }

        // Without the limit to one source, the strongest matches are all in Cases...
        search("zeppelin", 10)
                .andExpect(jsonPath("$.passages", hasSize(10)))
                .andExpect(jsonPath("$.passages[*].sourceId", everyItem(is(cases.toString()))));
        // ...which is exactly what filtering afterwards would have thrown away, leaving nothing.
        search("zeppelin", 10, policies)
                .andExpect(jsonPath("$.passages", hasSize(10)))
                .andExpect(jsonPath("$.passages[*].sourceId", everyItem(is(policies.toString()))))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    @DisplayName("a passage is found by a word whose stem is not its own stem")
    void findsWordsThatAreNotStableUnderStemming() throws Exception {
        UUID policies = createSource("Policies", false);
        upload(policies, "Pay.txt", "Remuneration is reviewed each April.");
        upload(policies, "Structure.txt", "The organisation is led by its board.");

        // Stemmed once, "remuneration" is "remuner", and stemmed again it is "remun", which no
        // passage contains. The query reads its stems as they are.
        search("remuneration", null)
                .andExpect(jsonPath("$.passages", hasSize(1)))
                .andExpect(jsonPath("$.passages[0].documentTitle").value("Pay.txt"));
        search("organisation", null)
                .andExpect(jsonPath("$.passages", hasSize(1)))
                .andExpect(jsonPath("$.passages[0].documentTitle").value("Structure.txt"));
    }

    @Test
    @DisplayName("a limit above 20, or below 1, is refused rather than quietly changed")
    void limitIsBounded() throws Exception {
        search("leave", 21).andExpect(status().isUnprocessableEntity());
        search("leave", 0).andExpect(status().isUnprocessableEntity());
        search("leave", 20).andExpect(status().isOk());
    }

    /* ---- Honest labels, and the vector store ------------------------------------------------- */

    @Test
    @DisplayName("a sandbox source is labelled keyword search and never reaches the vector store")
    void sandboxSourceIsKeywordOnly() throws Exception {
        UUID policies = createSource("Policies", false);
        upload(policies, "Leave.txt", LEAVE);

        mvc.perform(get("/api/sources/{id}", policies))
                .andExpect(jsonPath("$.embeddingProvider").value("sandbox"))
                .andExpect(jsonPath("$.searchMode").value("keyword"));
        search("leave", null)
                .andExpect(jsonPath("$.passages", hasSize(1)))
                .andExpect(jsonPath("$.degraded").value(false));

        verify(embeddings, never()).embedQuery(any(), anyString(), anyString(), anyString());
        verify(vectors, never()).search(anyString(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("a source with a real embedding model is labelled keyword and meaning")
    void embeddedSourceIsKeywordAndMeaning() throws Exception {
        UUID policies = createSource("Policies", false);
        upload(policies, "Leave.txt", LEAVE);
        setProvider(policies, "gemini");

        mvc.perform(get("/api/sources/{id}", policies)).andExpect(jsonPath("$.searchMode").value("keyword+meaning"));
        mvc.perform(get("/api/sources")).andExpect(jsonPath("$[0].searchMode").value("keyword+meaning"));
    }

    @Test
    @DisplayName("with the vector store failing, a search still answers from keywords and says it is degraded")
    void failingVectorStoreIsReported() throws Exception {
        UUID policies = createSource("Policies", false);
        upload(policies, "Leave.txt", LEAVE);
        setProvider(policies, "gemini");
        when(embeddings.embedQuery(any(), anyString(), anyString(), anyString())).thenReturn(new float[] {0.1f});
        when(vectors.search(anyString(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The knowledge base is unavailable."));

        search("leave", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.passages", hasSize(1)))
                .andExpect(jsonPath("$.passages[0].documentTitle").value("Leave.txt"));
    }

    @Test
    @DisplayName("a search asks the vector store for the sources the caller may read, and only those")
    void vectorSearchIsFilteredToAllowedSources() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID hr = createSource("HR", true);
        upload(policies, "Leave.txt", LEAVE);
        upload(hr, "Salary bands.txt", SALARY);
        setProvider(policies, "gemini");
        setProvider(hr, "gemini");
        when(embeddings.embedQuery(any(), anyString(), anyString(), anyString())).thenReturn(new float[] {0.1f});
        when(vectors.search(anyString(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        signedIn(org, EMPLOYEE);
        search("leave", null).andExpect(status().isOk()).andExpect(jsonPath("$.degraded").value(false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> searched = ArgumentCaptor.forClass(Collection.class);
        verify(vectors).search(anyString(), any(), searched.capture(), any(), anyInt(), any());
        assertThat(searched.getValue()).containsExactly(policies).doesNotContain(hr);
    }

    /* ---- A document's passages --------------------------------------------------------------- */

    private static String handbook() {
        StringBuilder text = new StringBuilder();
        String[] headings = {"Annual leave", "Sick leave", "Travel"};
        for (int section = 0; section < headings.length; section++) {
            text.append("# ").append(headings[section]).append("\n\n");
            for (int paragraph = 0; paragraph < 4; paragraph++) {
                text.append("Rule ").append(section).append('.').append(paragraph).append(" applies to every member of staff. ")
                        .append("It is reviewed each year by the people team and agreed with a manager. ".repeat(5))
                        .append("\n\n");
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("a document's passages come back in reading order, with page and heading, and can be paged")
    void passagesInOrder() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID handbook = upload(policies, "Handbook.md", handbook());
        int stored = chunks.findIdsByDocumentId(handbook).size();
        assertThat(stored).isGreaterThan(3);

        signedIn(org, EMPLOYEE);
        String body = mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(handbook.toString()))
                .andExpect(jsonPath("$.title").value("Handbook.md"))
                .andExpect(jsonPath("$.total").value(stored))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.passages", hasSize(stored)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> positions = JsonPath.read(body, "$.passages[*].position");
        assertThat(positions).isSorted().doesNotHaveDuplicates().first().isEqualTo(0);
        List<String> headings = JsonPath.read(body, "$.passages[*].heading");
        assertThat(headings).contains("Annual leave", "Travel");
        List<String> contents = JsonPath.read(body, "$.passages[*].content");
        assertThat(contents).allSatisfy(content -> assertThat(content).isNotBlank());

        // A page at a time: the second page starts where the first stopped.
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook).param("size", "2"))
                .andExpect(jsonPath("$.passages", hasSize(2)))
                .andExpect(jsonPath("$.passages[0].position").value(0))
                .andExpect(jsonPath("$.size").value(2));
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook)
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.passages[0].position").value(2));
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook).param("size", "0"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook).param("size", "201"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, handbook).param("page", "-1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("passages open only through the source their document belongs to, in the caller's workspace")
    void passagesEnforceOwnership() throws Exception {
        UUID policies = createSource("Policies", false);
        UUID travel = createSource("Travel", false);
        UUID leave = upload(policies, "Leave.txt", LEAVE);

        // Right source: found.
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, leave))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages", hasSize(1)));
        // Another source's id beside the document's: not found, though both are real.
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", travel, leave)).andExpect(status().isNotFound());
        // A document that does not exist, and a source that does not.
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", UUID.randomUUID(), leave))
                .andExpect(status().isNotFound());

        // Another workspace, both ids real: not found.
        signedIn(UUID.randomUUID(), MANAGER);
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, leave)).andExpect(status().isNotFound());

        // Reading needs knowledge:read.
        signedIn(org, Permission.Codes.KNOWLEDGE_QUERY);
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", policies, leave)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a restricted source's passages open for a manager and are not found for anyone else")
    void restrictedPassages() throws Exception {
        UUID hr = createSource("HR", true);
        UUID salary = upload(hr, "Salary bands.txt", SALARY);

        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", hr, salary))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages[0].content").value(startsWith("Salary bands")));

        signedIn(org, EMPLOYEE);
        mvc.perform(get("/api/sources/{s}/documents/{d}/chunks", hr, salary))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.passages").doesNotExist());
        assertThat(documents.findById(salary)).isPresent();
        assertThat(sources.findById(hr).orElseThrow().isRestricted()).isTrue();
        mvc.perform(get("/api/sources")).andExpect(jsonPath("$[*].name", not(contains("HR"))));
    }
}
