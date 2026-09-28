package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.voice.VoiceService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The one agent-configuration route the voice engineer owns.
 *
 * <p>Kept as its own controller rather than a method added to {@link AgentController}, so voice
 * work never edits a file the engine owns. It shares that class's {@code AgentView} and its
 * package-private {@code summarise} helper - both already written to be reused by exactly this
 * kind of sibling - and adds nothing else to the surface {@link AgentController} is responsible
 * for.
 */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agents")
public class AgentVoiceController {

    private final Agents agents;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final VoiceService voice;

    public AgentVoiceController(Agents agents, AgentVersions versions, ToolGrants grants, VoiceService voice) {
        this.agents = agents;
        this.versions = versions;
        this.grants = grants;
        this.voice = voice;
    }

    public record SetVoiceRequest(String voiceId) {}

    @PutMapping("/{agentId}/voice")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Set or clear the ElevenLabs voice an agent speaks with")
    public AgentController.AgentView setVoice(@PathVariable UUID agentId, @RequestBody SetVoiceRequest request) {
        UUID orgId = orgId();
        Agent agent = agents.findByIdAndOrgId(agentId, orgId)
                .orElseThrow(() -> ApiException.notFound("agent", agentId));

        String voiceId = request.voiceId() == null || request.voiceId().isBlank() ? null : request.voiceId();
        if (voiceId != null) {
            voice.validateVoiceId(orgId, voiceId);
        }
        agent.setVoiceId(voiceId);
        agents.save(agent);

        AgentVersion version = agent.getCurrentVersionId() == null
                ? null
                : versions.findById(agent.getCurrentVersionId()).orElse(null);
        return new AgentController.AgentView(
                agent.getId(), agent.getKey(), agent.getName(), agent.getCategory(), agent.getStatus(),
                version == null ? null : version.getRevision(),
                version == null ? null : AgentController.summarise(version.getSystemPrompt()),
                serverNames(agentId),
                agent.getVoiceId());
    }

    private List<String> serverNames(UUID agentId) {
        return grants.findByAgentIdAndEnabledTrue(agentId).stream()
                .map(AgentToolGrant::getServer)
                .distinct()
                .sorted()
                .toList();
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
