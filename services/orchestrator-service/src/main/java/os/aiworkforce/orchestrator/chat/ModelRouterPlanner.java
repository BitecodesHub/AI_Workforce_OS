package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.error.ApiException;

/**
 * Asks a live model to plan a short chain of agents for a piece of work, when the workspace has
 * one configured.
 *
 * <p>The planner only chooses agents and splits the work between them. Each agent is given the
 * person's own words in full by the coordinator, so a planned step's instruction is a short label
 * for that agent's part - never a paraphrase that would have to carry the request's content, and
 * that a long paste would cut off mid-JSON.
 *
 * <p>One call, asking for strict JSON against a schema built for this workspace's active agents.
 * The result is trusted only when it came from an actual provider - never the offline sandbox,
 * which cannot really read the request - and only when it names agents that exist and are active.
 * Anything else and this returns empty, which tells the coordinator to fall back to
 * {@link RuleRouter}.
 */
@Service
public class ModelRouterPlanner {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterPlanner.class);
    private static final int MAX_STEPS = 3;
    /**
     * A step's sentence is only a label - the prompt asks for 25 words - so a model that writes
     * more, or copies the request into it, is cut here rather than clutter a title and a card.
     */
    private static final int MAX_PART_CHARS = 300;
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?=\\s|$)");

    /** A reply that arrived fenced in a markdown code block, with an optional "json" tag. */
    private static final Pattern CODE_FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);

    private final ModelRouter router;
    private final RoutingPolicyResolver policies;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final ObjectMapper objectMapper;

    public ModelRouterPlanner(
            ModelRouter router,
            RoutingPolicyResolver policies,
            AgentVersions versions,
            ToolGrants grants,
            ObjectMapper objectMapper) {
        this.router = router;
        this.policies = policies;
        this.versions = versions;
        this.grants = grants;
        this.objectMapper = objectMapper;
    }

    public record PlannedStep(UUID agentId, String instruction) {}

    public record Plan(List<PlannedStep> steps, String reason) {}

    /**
     * A steer for the prompt beyond the agent catalog itself.
     *
     * @param lastAnswerAgent the agent whose reply was last in this conversation, so a short
     *     follow-up ("shorter", "now send it") can be kept with the agent it refers to; null when
     *     there is no such reply, or this is the first message
     */
    public record PlanHints(Agent lastAnswerAgent) {
        public static final PlanHints NONE = new PlanHints(null);
    }

    /** Empty when no live provider answered with a plan this coordinator can act on. */
    public Optional<Plan> plan(UUID orgId, String text, List<Agent> agents) {
        return plan(orgId, text, agents, PlanHints.NONE);
    }

    /** Empty when no live provider answered with a plan this coordinator can act on. */
    public Optional<Plan> plan(UUID orgId, String text, List<Agent> agents, PlanHints hints) {
        List<Agent> active = agents.stream()
                .filter(agent -> "active".equals(agent.getStatus()))
                .toList();
        if (active.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Agent> byKey = new LinkedHashMap<>();
        for (Agent agent : active) {
            if (agent.getKey() != null) {
                byKey.putIfAbsent(agent.getKey(), agent);
            }
        }

        Agent fallback =
                active.stream().filter(GeneralEmployee::isFallback).findFirst().orElse(null);
        String fallbackLine = fallback == null
                ? "- Choose the agent whose work the request is."
                : ("- Choose the specialist whose area the request touches, even when it is a question rather than a "
                                + "task, or the specialist would first need to ask for details: a question about customers "
                                + "belongs to the agent who works with customers, one about staff to the agent who works "
                                + "with people. Choose \"%s\" only when the request lies outside every specialist's area, "
                                + "such as general knowledge, small talk, or work none of them does.")
                        .formatted(fallback.getKey());
        PlanHints effectiveHints = hints == null ? PlanHints.NONE : hints;
        String hintLine = effectiveHints.lastAnswerAgent() == null
                ? ""
                : ("The last reply in this conversation came from %s (key %s). A short follow-up that refers to "
                                + "that reply, such as \"shorter\", \"now send it\" or \"add a table\", belongs to the same agent.")
                        .formatted(
                                effectiveHints.lastAnswerAgent().getName(),
                                effectiveHints.lastAnswerAgent().getKey());

        String catalog = active.stream().map(this::describe).collect(Collectors.joining("\n"));
        String system =
                """
                You route one request from a person to between one and three of the agents below, in the order they should work.
                Rules:
                %s
                - Never leave a request unrouted. For a vague request, route it to the agent that would do the work; that agent can ask the person a question.
                - Use each agent at most once. Use more than one agent only when the request asks for work done in sequence.
                - Each agent is given the person's whole request word for word, so "instruction" is only that agent's part of it: one short sentence of at most 25 words. Never copy names, figures, pasted text or any other content from the request into it.
                - Use only the agent keys listed; never invent one.
                %s
                Reply with only a JSON object of this shape, and nothing else:
                {"plan":[{"agentKey":"<key>","instruction":"<this agent's part, one short sentence>"}],"reason":"<one sentence>"}

                Agents:
                %s"""
                        .formatted(fallbackLine, hintLine, catalog);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.system(system), ChatMessage.user(text)))
                .jsonSchema(schemaFor(byKey.keySet()))
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

    /** The plan schema, built per call so the model can only name a key that actually exists. */
    private static String schemaFor(Set<String> activeKeys) {
        String enumJson = activeKeys.stream()
                .map(key -> "\"" + key.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        return """
                {"type":"object","additionalProperties":false,"required":["plan","reason"],"properties":{
                "plan":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object","additionalProperties":false,
                "required":["agentKey","instruction"],"properties":{"agentKey":{"type":"string","enum":[%s]},
                "instruction":{"type":"string"}}}},
                "reason":{"type":"string"}}}"""
                .formatted(enumJson);
    }

    private Optional<Plan> parse(String content, Map<String, Agent> byKey) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(cleanJson(content));
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
                String part = CoordinatorService.truncateAtWord(instruction, MAX_PART_CHARS);
                steps.add(new PlannedStep(agent.getId(), part));
            }
            String reason = root.path("reason").asText("").strip();
            return Optional.of(new Plan(steps, reason.isBlank() ? "The model chose this routing." : reason));
        } catch (Exception malformed) {
            log.debug("The model's routing reply was not the JSON it was asked for: {}", malformed.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Strips a reply down to the JSON object it should contain: leading and trailing whitespace,
     * then a surrounding markdown code fence when present, then everything outside the outermost
     * braces. A model that decorates its answer with prose or formatting still parses.
     */
    private static String cleanJson(String content) {
        String stripped = content.strip();
        Matcher fence = CODE_FENCE.matcher(stripped);
        String unfenced = fence.matches() ? fence.group(1) : stripped;
        int start = unfenced.indexOf('{');
        int end = unfenced.lastIndexOf('}');
        if (start < 0 || end < start) {
            return unfenced;
        }
        return unfenced.substring(start, end + 1);
    }

    private String describe(Agent agent) {
        String does = "";
        if (agent.getCurrentVersionId() != null) {
            AgentVersion version =
                    versions.findById(agent.getCurrentVersionId()).orElse(null);
            if (version != null) {
                does = firstSentence(version.getSystemPrompt());
            }
        }
        String tools = grants.findByAgentIdAndEnabledTrue(agent.getId()).stream()
                .map(AgentToolGrant::getServer)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        String fallbackNote =
                GeneralEmployee.isFallback(agent) ? " (default: choose when no other agent clearly fits)" : "";
        return "- key: %s, name: %s, category: %s, does: %s, tools: %s%s"
                .formatted(agent.getKey(), agent.getName(), agent.getCategory(), does, tools, fallbackNote);
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
