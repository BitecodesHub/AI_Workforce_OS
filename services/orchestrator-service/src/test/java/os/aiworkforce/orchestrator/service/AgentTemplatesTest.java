package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.platform.config.PlatformProperties;

/** The catalogue the demo workspace and every real workspace start from. */
class AgentTemplatesTest {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Test
    @DisplayName("four templates, each with a key AgentController would accept, and a prompt that says how it works")
    void theCatalogueIsWellFormed() {
        List<AgentTemplates.Template> all = AgentTemplates.all();

        assertThat(all).extracting(AgentTemplates.Template::key)
                .containsExactly("hr", "engineering-manager", "research", "support");
        assertThat(all).allSatisfy(template -> {
            assertThat(template.key()).matches(KEY).hasSizeLessThanOrEqualTo(60);
            assertThat(template.name()).isNotBlank().hasSizeLessThanOrEqualTo(120);
            assertThat(template.category()).isIn("operations", "engineering", "growth", "support");
            assertThat(template.description()).isNotBlank();
            assertThat(template.prompt()).contains("How you work").hasSizeLessThanOrEqualTo(20_000);
        });
    }

    @Test
    @DisplayName("everything a demo agent is granted is something worth suggesting, except voice notes")
    void suggestionsCoverTheDemoGrants() {
        for (AgentTemplates.Template template : AgentTemplates.all()) {
            List<String> granted = template.demoGrants().stream()
                    .map(AgentTemplates.Grant::server)
                    .filter(server -> !server.equals("voice"))
                    .toList();
            assertThat(template.suggestedConnectors()).as(template.key()).containsAll(granted);
        }
    }

    @Test
    @DisplayName("a template is found by its key, and an unknown or missing key finds nothing")
    void findsByKey() {
        assertThat(AgentTemplates.find("research")).isPresent();
        assertThat(AgentTemplates.find("nobody")).isEmpty();
        assertThat(AgentTemplates.find(null)).isEmpty();
    }

    @Test
    @DisplayName("the demo seeder writes the catalogue's own prompts and grants, into the demo workspace only")
    void theSeederUsesTheCatalogue() {
        UUID demo = DemoAgentSeeder.DEMO_ORG_ID;
        Agents agents = mock(Agents.class);
        AgentVersions versions = mock(AgentVersions.class);
        ToolGrants grants = mock(ToolGrants.class);
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.environment()).thenReturn(PlatformProperties.Environment.LOCAL);
        when(agents.findByOrgIdAndKey(any(), any())).thenReturn(Optional.empty());
        when(grants.findByAgentIdAndServer(any(), any())).thenReturn(Optional.empty());
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        List<AgentVersion> savedVersions = new ArrayList<>();
        when(versions.save(any())).thenAnswer(call -> {
            savedVersions.add(call.getArgument(0));
            return call.getArgument(0);
        });
        List<AgentToolGrant> savedGrants = new ArrayList<>();
        when(grants.save(any())).thenAnswer(call -> {
            savedGrants.add(call.getArgument(0));
            return call.getArgument(0);
        });

        new DemoAgentSeeder(agents, versions, grants, properties, mock(GeneralEmployee.class), transactions).seed();

        assertThat(savedVersions).extracting(AgentVersion::getSystemPrompt)
                .containsExactlyElementsOf(AgentTemplates.all().stream().map(AgentTemplates.Template::prompt).toList());
        // Every grant is in the demo workspace; the seeder has no other workspace to give one to.
        assertThat(savedGrants).isNotEmpty().allSatisfy(grant -> assertThat(grant.getOrgId()).isEqualTo(demo));
        verify(agents, never()).findByOrgIdAndKey(eq(UUID.fromString("00000000-0000-7000-8000-0000000000a1")), any());
    }
}
