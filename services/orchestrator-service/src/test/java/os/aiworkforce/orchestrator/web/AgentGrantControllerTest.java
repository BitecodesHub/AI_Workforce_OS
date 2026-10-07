package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.policy.ArgumentValidator;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.policy.ToolGrant;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/** Granting a connector to an agent: only real names, and scopes that match the tools. */
class AgentGrantControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000cc");

    private ToolGrants grants;
    private AuditClient audit;
    private AgentController details;
    private ToolGateway gateway;
    private AgentGrantController controller;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper();
        gateway = new ToolGateway(
                SandboxServerRegistry.servers(json, WebClient.builder(), true),
                new ArgumentValidator(json),
                new ResiliencePresets(mock(PlatformProperties.class)));
        Agents agents = mock(Agents.class);
        Agent agent = new Agent();
        agent.setId(AGENT);
        agent.setOrgId(ORG);
        when(agents.findByIdAndOrgId(AGENT, ORG)).thenReturn(Optional.of(agent));
        grants = mock(ToolGrants.class);
        when(grants.findByAgentIdAndServer(eq(AGENT), any())).thenReturn(Optional.empty());
        audit = mock(AuditClient.class);
        details = mock(AgentController.class);
        when(details.get(AGENT)).thenReturn(detail());
        controller = new AgentGrantController(agents, grants, gateway, details, audit);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "owner", Set.of(), 0L));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("an unknown connector is refused with 422 naming it")
    void unknownServer() {
        assertThatThrownBy(() -> controller.grant(AGENT, "myspace", request(List.of(), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.details()).containsEntry("field", "server").containsEntry("value", "myspace");
                });
        verify(grants, never()).save(any());
    }

    @Test
    @DisplayName("an unknown capability is refused with 422 naming it")
    void unknownTool() {
        assertThatThrownBy(() -> controller.grant(AGENT, "github", request(List.of("list_issues", "drop_database"), 5)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.details()).containsEntry("field", "tools").containsEntry("value", "drop_database");
                    assertThat(e.getMessage()).contains("drop_database");
                });
        verify(grants, never()).save(any());
    }

    @Test
    @DisplayName("scopes come from the chosen tools, so the gateway offers exactly what was granted")
    void scopesFilledFromTools() {
        controller.grant(AGENT, "github", request(List.of("list_issues", "get_pulls"), 20));

        AgentToolGrant saved = saved();
        assertThat(saved.getAllowedTools()).containsExactly("list_issues", "get_pulls");
        assertThat(saved.getScopes()).containsExactly("repo:read");
        assertThat(saved.getMaxCallsPerRun()).isEqualTo(20);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(gateway.availableTools(List.of(asGrant(saved))))
                .extracting(tool -> tool.name())
                .containsExactlyInAnyOrder("list_issues", "get_pulls");
    }

    @Test
    @DisplayName("no tools named means every tool, with every scope the server needs")
    void emptyMeansEverything() {
        controller.grant(AGENT, "hubspot", request(List.of(), null));

        AgentToolGrant saved = saved();
        assertThat(saved.getAllowedTools()).isEmpty();
        assertThat(saved.getScopes())
                .containsExactlyInAnyOrder(
                        "crm.objects.contacts.read", "crm.objects.contacts.write", "crm.objects.deals.read");
        assertThat(gateway.availableTools(List.of(asGrant(saved)))).hasSize(4);
        verify(audit, org.mockito.Mockito.timeout(1000))
                .record(eq(ORG), any(), eq("agent.grant.add"), eq("agent"), eq(AGENT.toString()), eq("succeeded"), anyMap());
    }

    @Test
    @DisplayName("granting again updates the existing grant rather than adding a second one")
    void upsert() {
        AgentToolGrant existing = new AgentToolGrant();
        existing.setAgentId(AGENT);
        existing.setOrgId(ORG);
        existing.setServer("slack");
        existing.setAllowedTools(List.of("post_message"));
        when(grants.findByAgentIdAndServer(AGENT, "slack")).thenReturn(Optional.of(existing));

        AgentController.AgentDetail answer = controller.grant(
                AGENT, "slack", new AgentGrantController.GrantRequest(List.of("list_channels"), true, null));

        assertThat(saved()).isSameAs(existing);
        assertThat(existing.getAllowedTools()).containsExactly("list_channels");
        assertThat(existing.getScopes()).containsExactly("channels:read");
        assertThat(existing.isRequireApproval()).isTrue();
        assertThat(answer.id()).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("removing a grant deletes it and returns the agent")
    void revoke() {
        AgentToolGrant existing = new AgentToolGrant();
        existing.setServer("github");
        when(grants.findByAgentIdAndServer(AGENT, "github")).thenReturn(Optional.of(existing));

        AgentController.AgentDetail answer = controller.revoke(AGENT, "github");

        verify(grants).delete(existing);
        assertThat(answer.id()).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("an agent in another workspace is not found")
    void otherWorkspace() {
        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "owner", Set.of(), 0L));

        assertThatThrownBy(() -> controller.grant(AGENT, "github", request(List.of(), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    @DisplayName("the per-run call limit must be between 1 and 1000, and may be left out")
    void callLimitBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request(List.of(), 0))).isNotEmpty();
            assertThat(validator.validate(request(List.of(), 1001))).isNotEmpty();
            assertThat(validator.validate(request(List.of(), 20))).isEmpty();
            assertThat(validator.validate(request(List.of(), null))).isEmpty();
        }
    }

    private AgentToolGrant saved() {
        ArgumentCaptor<AgentToolGrant> captor = ArgumentCaptor.forClass(AgentToolGrant.class);
        verify(grants).save(captor.capture());
        return captor.getValue();
    }

    private static ToolGrant asGrant(AgentToolGrant grant) {
        return new ToolGrant(
                AGENT.toString(),
                grant.getServer(),
                grant.getAllowedTools(),
                grant.getScopes(),
                grant.isRequireApproval(),
                grant.getMaxCallsPerRun(),
                grant.isEnabled());
    }

    private static AgentGrantController.GrantRequest request(List<String> tools, Integer maxCallsPerRun) {
        return new AgentGrantController.GrantRequest(tools, false, maxCallsPerRun);
    }

    private static AgentController.AgentDetail detail() {
        return new AgentController.AgentDetail(
                AGENT, "support", "Support", "support", "active", 1, "You help.", "", 12, false, List.of(), "You help.",
                List.of(), null, false);
    }
}
