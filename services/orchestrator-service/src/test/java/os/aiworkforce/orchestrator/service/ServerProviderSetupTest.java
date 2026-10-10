// @find: tests for server provider setup, Ollama base url at startup, enable Bedrock with instance role
// @what: Checks start-up writes only what the environment asks for, and nothing at all when it asks for nothing.
package os.aiworkforce.orchestrator.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ServerProviderSetupTest {

    @Test
    @DisplayName("writes nothing when the deployment sets none of the variables")
    void nothingConfigured() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ServerProviderSetup(jdbc, ServerRouting.none(), Map.of()).applyNow();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("points Ollama at AIWOS_OLLAMA_BASE_URL, turns it and Bedrock on by default, and adds the model row")
    void appliesServerSettings() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("bedrock"), eq("apac.amazon.nova-lite-v1:0")))
                .thenReturn(1);
        ServerRouting routing =
                new ServerRouting(true, "http://ollama:11434/v1", "llama3.2:3b", "bedrock/apac.amazon.nova-lite-v1:0");

        new ServerProviderSetup(jdbc, routing, Map.of("AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS", "true")).applyNow();

        verify(jdbc).update(contains("SET base_url = ?"), eq("http://ollama:11434/v1"), eq("ollama"),
                eq("http://ollama:11434/v1"));
        verify(jdbc).update(contains("workspace_default_enabled = TRUE"), eq("ollama"));
        verify(jdbc).update(contains("workspace_default_enabled = TRUE"), eq("bedrock"));
        verify(jdbc).update(contains("INSERT INTO llm_models"), eq("ollama"), eq("llama3.2:3b"), eq("llama3.2:3b (local)"));
    }

    @Test
    @DisplayName("leaves Bedrock alone when the server's AWS role is not to be used")
    void bedrockOnlyWithTheSwitch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ServerProviderSetup(jdbc, new ServerRouting(true, "", "qwen2.5:1.5b-instruct", ""), Map.of()).applyNow();
        verify(jdbc, never()).update(contains("workspace_default_enabled = TRUE"), eq("bedrock"));
        verify(jdbc, never()).update(contains("SET base_url = ?"), anyString(), anyString(), anyString());
    }
}
