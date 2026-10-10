// @find: tests for Ollama model list, /api/tags, local models, keyless provider catalogue
// @what: Checks the local model server's list is read from /api/tags with no key, and its models are free and tool-capable by family.
package os.aiworkforce.orchestrator.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;

class OllamaCatalogueTest {

    private static final String TAGS = """
            {"models":[
              {"name":"qwen2.5:1.5b-instruct","model":"qwen2.5:1.5b-instruct","size":986061892,
               "details":{"family":"qwen2","parameter_size":"1.5B","quantization_level":"Q4_K_M"}},
              {"name":"nomic-embed-text:latest","details":{"family":"nomic-bert","parameter_size":"137M"}},
              {"name":"tinyllama:1.1b","details":{"parameter_size":"1.1B"}}
            ]}
            """;

    @Test
    @DisplayName("reads /api/tags: embedding models left out, free, tool calling from the family")
    void parsesTags() throws Exception {
        List<CatalogModel> models = ModelListParsers.ollamaTags(new ObjectMapper().readTree(TAGS));

        assertThat(models).extracting(CatalogModel::id).containsExactly("qwen2.5:1.5b-instruct", "tinyllama:1.1b");
        CatalogModel qwen = models.get(0);
        assertThat(qwen.free()).isTrue();
        assertThat(qwen.toolCalling()).isTrue();
        assertThat(qwen.contextLength()).isEqualTo(ModelListParsers.OLLAMA_CONTEXT);
        assertThat(qwen.displayName()).endsWith("1.5B (local)");
        assertThat(models.get(1).toolCalling()).isFalse();
    }

    @Test
    @DisplayName("finds Ollama by id or port, and its root from the OpenAI-compatible base URL")
    void flavourAndRoot() {
        LlmProviderEntity ollama = new LlmProviderEntity();
        ollama.setId("ollama");
        ollama.setBaseUrl("http://ollama:11434/v1");
        assertThat(ModelCatalogService.flavour(ollama)).isEqualTo("ollama");
        LlmProviderEntity custom = new LlmProviderEntity();
        custom.setId("my-box");
        custom.setBaseUrl("http://10.0.0.5:11434/v1/");
        assertThat(ModelCatalogService.flavour(custom)).isEqualTo("ollama");
        assertThat(ModelCatalogService.ollamaRoot("http://ollama:11434/v1/")).isEqualTo("http://ollama:11434");
        assertThat(ModelCatalogService.ollamaRoot("http://ollama:11434")).isEqualTo("http://ollama:11434");
    }

    @Test
    @DisplayName("lists the local models live, from /api/tags, without asking the key store")
    void listsWithoutAKey() {
        UUID workspace = UUID.randomUUID();
        LlmProviderEntity ollama = new LlmProviderEntity();
        ollama.setId("ollama");
        ollama.setDisplayName("Ollama (local)");
        ollama.setKind("OPENAI_COMPATIBLE");
        ollama.setBaseUrl("http://ollama:11434/v1");
        JpaProviderRegistry registry = mock(JpaProviderRegistry.class);
        when(registry.workspaceProvider(workspace, "ollama"))
                .thenReturn(Optional.of(new JpaProviderRegistry.WorkspaceProvider(ollama, true, "unknown", null)));
        CredentialResolver credentials = mock(CredentialResolver.class);
        List<URI> asked = new ArrayList<>();
        List<Map<String, String>> headers = new ArrayList<>();
        ModelListHttp http = (uri, sent) -> {
            asked.add(uri);
            headers.add(sent);
            return new ModelListHttp.Response(200, TAGS);
        };
        ModelCatalogService catalogue = new ModelCatalogService(
                registry,
                mock(Models.class),
                credentials,
                mock(ModelCatalogStore.class),
                new ObjectMapper(),
                http,
                Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC));

        ModelCatalogService.CatalogueView view = catalogue.list(workspace, "ollama", true, false);

        assertThat(view.source()).isEqualTo(ModelCatalogService.Source.live);
        assertThat(view.models()).extracting(ModelCatalogService.ModelOption::id).containsExactly("qwen2.5:1.5b-instruct");
        assertThat(asked).containsExactly(URI.create("http://ollama:11434/api/tags"));
        assertThat(headers.get(0)).doesNotContainKey("Authorization");
        org.mockito.Mockito.verifyNoInteractions(credentials);
    }
}
