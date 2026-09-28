package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.error.ApiException;

/**
 * Asks a live model to plan a short chain of agents for a piece of work, when the workspace has
 * one configured.
 *
 * <p>One call, asking for strict JSON against a fixed schema. The result is trusted only when it
 * came from an actual provider - never the offline sandbox, which cannot really read the request -
 * and only when it names agents that exist and are active. Anything else and this returns empty,
 * which tells the coordinator to fall back to {@link RuleRouter}.
 */
@Service
public class ModelRouterPlanner {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterPlanner.class);
    private static final int MAX_STEPS = 3;
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?=\\s|$)");

    private static final String JSON_SCHEMA = """
            {"type":"object","properties":{"plan":{"type":"array","minItems":1,"maxItems":3,\
            "items":{"type":"object","properties":{"agentKey":{"type":"string"},\
            "instruction":{"type":"string"}},"required":["agentKey","instruction"]}},\
            "reason":{"type":"string"}},"required":["plan","reason"]}""";

    private final ModelRouter router;
    private final RoutingPolicyResolver policies;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final ObjectMapper objectMapper;

    public ModelRouterPlanner(
            ModelRouter router, RoutingPolicyResolver policies, AgentVersions versions,
            ToolGrants grants, ObjectMapper objectMapper) {
        this.router = router;
        this.policies = policies;
        this.versions = versions;
        this.grants = grants;
        this.objectMapper = objectMapper;
    }

    public record PlannedStep(UUID agentId, String instruction) {}

    public record Plan(List<PlannedStep> steps, String reason) {}

    /** Empty when no live provider answered with a plan this coordinator can act on. */
    public Optional<Plan> plan(UUID orgId, String text, List<Agent> agents) {
        List<Agent> active = agents.stream().filter(agent -> "active".equals(agent.getStatus())).toList();
        if (active.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Agent> byKey = new java.util.LinkedHashMap<>();
        for (Agent agent : active) {
            if (agent.getKey() != null) {
                byKey.putIfAbsent(agent.getKey(), agent);
            }
        }

        String catalog = active.stream().map(this::describe).collect(Collectors.joining("\n"));
        String system = """
                You route one request from a person to between one and three of the agents below, \
                in the order they should work. Reply with only the JSON shape you were given. \
                Use only the agent keys listed; never invent one.

                Agents:
                %s""".formatted(catalog);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.system(system), ChatMessage.user(text)))
                .jsonSchema(JSON_SCHEMA)
                .maxOutputTokens(800)
                .timeout(Duration.ofSeconds(20))
                .build();

        RoutingPolicy policy = policies.resolve(orgId, null);
        ModelRouter.CallContext context = new ModelRouter.CallContext(orgId.toString(), null, null);

        ChatResponse response;
        try {
            response = router.route(request, policy, context);
        } catch (ApiException e) {
            log.debug("The model router had nothing to plan this chat message with: {}", e.getMessage());
            return Optional.empty();
        }

        if ("sandbox".equals(response.provider())) {
            // The offline model cannot really read the request; treating its guess as a plan
            // would route work by chance rather than by anything the person actually wrote.
            return Optional.empty();
        }

        return parse(response.content(), byKey);
    }

    private Optional<Plan> parse(String content, Map<String, Agent> byKey) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode planNode = root.get("plan");
            if (planNode == null || !planNode.isArray() || planNode.isEmpty() || planNode.size() > MAX_STEPS) {
                return Optional.empty();
            }
            List<PlannedStep> steps = new ArrayList<>();
            for (JsonNode stepNode : planNode) {
                String agentKey = stepNode.path("agentKey").asText(null);
                String instruction = stepNode.path("instruction").asText(null);
                if (agentKey == null || instruction == null || instruction.isBlank()) {
                    return Optional.empty();
                }
                Agent agent = byKey.get(agentKey);
                if (agent == null) {
                    return Optional.empty();
                }
                steps.add(new PlannedStep(agent.getId(), instruction.strip()));
            }
            String reason = root.path("reason").asText("").strip();
            return Optional.of(new Plan(steps, reason.isBlank() ? "The model chose this routing." : reason));
        } catch (Exception malformed) {
            log.debug("The model's routing reply was not the JSON it was asked for: {}", malformed.getMessage());
            return Optional.empty();
        }
    }

    private String describe(Agent agent) {
        String does = "";
        if (agent.getCurrentVersionId() != null) {
            AgentVersion version = versions.findById(agent.getCurrentVersionId()).orElse(null);
            if (version != null) {
                does = firstSentence(version.getSystemPrompt());
            }
        }
        String tools = grants.findByAgentIdAndEnabledTrue(agent.getId()).stream()
                .map(AgentToolGrant::getServer)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return "- key: %s, name: %s, category: %s, does: %s, tools: %s".formatted(
                agent.getKey(), agent.getName(), agent.getCategory(), does, tools);
    }

    private static String firstSentence(String prompt) {
        if (prompt == null) {
            return "";
        }
        String text = prompt.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "";
        }
        Matcher sentence = FIRST_SENTENCE.matcher(text);
        String first = sentence.find() ? sentence.group(1) : text;
        return first.length() <= 160 ? first : first.substring(0, 160).strip() + "…";
    }
}
