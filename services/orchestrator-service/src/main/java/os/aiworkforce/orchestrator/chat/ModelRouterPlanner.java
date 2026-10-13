// @find: model router planner, ask a model to plan agents, chain of agents, plan steps, route with LLM, ModelRouterPlanner, which agent handles this, multi-agent plan
// @what: Asks a configured live model to choose and order agents for a piece of work.
// @flow: Called by CoordinatorService, which falls back to RuleRouter when this returns empty; calls ModelRouter (one LLM call), AgentVersions and ToolGrants to describe the agents.
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

    /** Logger for planning failures, kept at debug because falling back to the rules is normal. */
    private static final Logger log = LoggerFactory.getLogger(ModelRouterPlanner.class);
    /** The most steps a plan may hold; a longer one is rejected. */
    private static final int MAX_STEPS = 3;
    /** The overall time all planning attempts together may take. */
    private static final Duration PLANNING_DEADLINE = Duration.ofSeconds(45);
    /** How long one model is given to answer the routing question. */
    static final Duration PLANNING_ATTEMPT = Duration.ofSeconds(15);
    /**
     * The most models asked to plan. The router asks every candidate it is given at least once, so
     * this, times {@link #PLANNING_ATTEMPT}, is what bounds the wait: three models, 45 seconds.
     */
    static final int MAX_PLANNING_CANDIDATES = 3;
    /**
     * A step's sentence is only a label - the prompt asks for 25 words - so a model that writes
     * more, or copies the request into it, is cut here rather than clutter a title and a card.
     */
    private static final int MAX_PART_CHARS = 300;
    /** Matches the first sentence of an agent's system prompt, used as its one-line description. */
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?=\\s|$)");

    /** The tool server that opens and reads websites. */
    static final String BROWSER = "browser";

    /**
     * A web address in the request: a link, "www.", or a domain such as wikipedia.org. A domain
     * inside an email address (anna@example.com) is not one: that request is about email.
     */
    private static final Pattern WEB_ADDRESS = Pattern.compile(
            "(?i)\\bhttps?://|\\bwww\\.|(?<![@.\\w-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\."
                    + "(?:com|org|net|io|ai|co|gov|edu|dev|app|info|me|uk|au|in|de|fr|ca|us)\\b");

    /**
     * Phrases that ask to go on the web: search or browse it, use the browser, or open, visit or
     * search a website or a site people name without its address. Only asking to go there counts:
     * "write the text for our website" is writing, and stays with whoever writes.
     */
    private static final Pattern WEB_WORDS = Pattern.compile(
            "(?i)\\b(?:search (?:the web|the internet|online)|look (?:it |this |that )?up online"
                    + "|browse (?:the )?(?:web|internet)|(?:in|with|use) (?:the |a )?browser"
                    + "|(?:open|visit|go to)\\s+(?:the\\s+)?(?:\\w+\\s+)?(?:website|web ?site|web ?page|wikipedia|youtube)"
                    + "|(?:search|on) (?:wikipedia|youtube))\\b");

    /** A reply that arrived fenced in a markdown code block, with an optional "json" tag. */
    private static final Pattern CODE_FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);

    private final ModelRouter router;
    private final RoutingPolicyResolver policies;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final ObjectMapper objectMapper;

    /** Wires the model router, the workspace's routing policy, and the repositories that describe each agent. */
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

    /** One step of a plan: the agent chosen and a short label for its part of the work. */
    public record PlannedStep(UUID agentId, String instruction) {}

    /** The ordered steps the model chose, with its one-sentence reason. */
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

    /** Plans without hints; see the overload with hints. Empty when no live provider answered with a plan this coordinator can act on. */
    // @find: plan agents for message
    public Optional<Plan> plan(UUID orgId, String text, List<Agent> agents) {
        return plan(orgId, text, agents, PlanHints.NONE);
    }

    /**
     * Asks a live model to choose and order agents for the message. Empty when no live provider
     * answered with a plan this coordinator can act on.
     *
     * <p>Flow: keeps the active agents, builds a system prompt (rules, the General Employee
     * fallback line, an optional hint about the agent that answered last, and a catalog of agents)
     * and a JSON schema limited to the real agent keys, then makes one call through the model
     * router under {@link #forPlanning}. The reply is discarded when it came from the offline
     * sandbox, and is otherwise parsed and validated. Called by {@link CoordinatorService}.
     *
     * <p>Side effects: one model call, which is billed and counted by the router. Failure: any
     * routing error, a sandbox answer, malformed JSON or an unknown agent key yields an empty
     * result, never an exception.
     *
     * @param hints a steer for follow-up messages; null is treated as none
     */
    // @find: plan agents for message with hints from last answer
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

        Map<UUID, List<String>> servers = new LinkedHashMap<>();
        for (Agent agent : active) {
            servers.put(agent.getId(), toolServers(agent));
        }
        String catalog = active.stream()
                .map(agent -> describe(agent, servers.get(agent.getId())))
                .collect(Collectors.joining("\n"));
        String system =
                """
                You route one request from a person to between one and three of the agents below, in the order they should work.
                Rules:
                %s
                - Never leave a request unrouted. For a vague request, route it to the agent that would do the work; that agent can ask the person a question.
                - Use each agent at most once. Use more than one agent only when the request asks for work done in sequence.
                - Each agent is given the person's whole request word for word, so "instruction" is only that agent's part of it: one short sentence of at most 25 words. Never copy names, figures, pasted text or any other content from the request into it.
                - Use only the agent keys listed; never invent one.
                - A request that needs a website, a web page or a search on the web goes to an agent whose tools include "browser". One that needs another outside system, such as GitHub, email or phone calls, goes to an agent holding that tool. An agent with "tools: none" works only from what it knows and the workspace's documents, and cannot open anything.
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
                .timeout(PLANNING_ATTEMPT)
                .build();

        RoutingPolicy policy = forPlanning(policies.resolve(orgId, null));
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

        return parse(response.content(), byKey)
                .map(plan -> withBrowsingAgent(plan, text, active, fallback, servers));
    }

    /**
     * Makes sure a request that names a website, or asks for the web, is planned to an agent that
     * can open it. A model sometimes routes "Open wikipedia.org and summarise the article" to the
     * agent whose work sounds closest (one that writes summaries), which has no browser and then
     * answers from memory: seen live on 13 Oct 2026.
     *
     * <p>Flow: when the request needs the web, no planned agent holds the browser and an active one
     * does, the step whose label asks for the web (else the first step) goes to the General
     * Employee when it can browse, otherwise to the first active agent that can. Every other plan
     * is returned unchanged, as is one where no agent can browse: that agent then says so. Pure;
     * logs the change at info.
     *
     * @param servers the tool servers each active agent holds, by agent id
     */
    static Plan withBrowsingAgent(
            Plan plan, String text, List<Agent> active, Agent fallback, Map<UUID, List<String>> servers) {
        if (!needsWeb(text)) {
            return plan;
        }
        if (plan.steps().stream().anyMatch(step -> canBrowse(step.agentId(), servers))) {
            return plan;
        }
        Agent browsing = fallback != null && canBrowse(fallback.getId(), servers)
                ? fallback
                : active.stream().filter(agent -> canBrowse(agent.getId(), servers)).findFirst().orElse(null);
        if (browsing == null) {
            return plan;
        }
        int index = 0;
        for (int i = 0; i < plan.steps().size(); i++) {
            if (needsWeb(plan.steps().get(i).instruction())) {
                index = i;
                break;
            }
        }
        List<PlannedStep> steps = new ArrayList<>(plan.steps());
        PlannedStep moved = steps.get(index);
        steps.set(index, new PlannedStep(browsing.getId(), moved.instruction()));
        log.info("The plan gave a web request to an agent without the browser; step {} goes to {} instead",
                index + 1, browsing.getName());
        return new Plan(List.copyOf(steps),
                "It needs a website, so " + browsing.getName() + ", who can open one, takes it.");
    }

    /** True when the text names a web address, or asks to search, browse or open the web or a website. */
    static boolean needsWeb(String text) {
        return text != null && (WEB_ADDRESS.matcher(text).find() || WEB_WORDS.matcher(text).find());
    }

    /** True when the agent holds an enabled browser grant. */
    private static boolean canBrowse(UUID agentId, Map<UUID, List<String>> servers) {
        return servers.getOrDefault(agentId, List.of()).contains(BROWSER);
    }

    /** The distinct tool servers the agent has enabled, sorted; read from the database. */
    private List<String> toolServers(Agent agent) {
        return grants.findByAgentIdAndEnabledTrue(agent.getId()).stream()
                .map(AgentToolGrant::getServer)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The workspace's first models, asked once each and within 45 seconds in all. The person is
     * watching "Finding the right agent" while this runs, and a model that has not answered a short
     * routing question in 15 seconds will not do better on a second try; the next model is asked
     * instead, and if none answers the rules route the message. Only planning is cut short like
     * this: the agent's own steps still ask every candidate in the list.
     */
    static RoutingPolicy forPlanning(RoutingPolicy workspace) {
        Duration deadline = workspace.overallDeadline() == null || workspace.overallDeadline().compareTo(PLANNING_DEADLINE) > 0
                ? PLANNING_DEADLINE
                : workspace.overallDeadline();
        List<RoutingPolicy.Candidate> first = workspace.candidates().stream()
                .limit(MAX_PLANNING_CANDIDATES)
                .toList();
        return new RoutingPolicy(first, workspace.exhausted(), 1, deadline, workspace.compactOnOverflow());
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

    /**
     * Turns the model's reply into a plan, or empty when it is blank, malformed, has no steps or
     * more than three, or names an agent that is not active. Filler steps and repeated agents are
     * skipped, and each instruction is cut to a short label.
     */
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
            Set<UUID> planned = new java.util.HashSet<>();
            for (JsonNode stepNode : planNode) {
                String agentKey = stepNode.path("agentKey").asText(null);
                String instruction = stepNode.path("instruction").asText(null);
                if (agentKey == null || instruction == null) {
                    return Optional.empty();
                }
                Agent agent = byKey.get(agentKey);
                if (agent == null) {
                    return Optional.empty();
                }
                if (isFiller(instruction) || !planned.add(agent.getId())) {
                    // A model held to a schema sometimes pads the array with steps it gave no
                    // part to ("--", "", "N/A") or repeats an agent; those are not work, and
                    // running them would answer one question three times.
                    continue;
                }
                String part = CoordinatorService.truncateAtWord(instruction, MAX_PART_CHARS);
                steps.add(new PlannedStep(agent.getId(), part));
            }
            if (steps.isEmpty()) {
                return Optional.empty();
            }
            String reason = root.path("reason").asText("").strip();
            return Optional.of(new Plan(steps, reason.isBlank() ? "The model chose this routing." : reason));
        } catch (Exception malformed) {
            log.debug("The model's routing reply was not the JSON it was asked for: {}", malformed.getMessage());
            return Optional.empty();
        }
    }

    /** Matches an instruction that is blank, only punctuation, or a placeholder such as "n/a" or "none". */
    private static final Pattern FILLER =
            Pattern.compile("(?i)^[\\s\\p{Punct}]*(?:n/?a|none|null|nothing|skip|tbd|no action|not needed)?[\\s\\p{Punct}]*$");

    /** A step the model left without a real part: blank, only punctuation, or a "none" placeholder. */
    static boolean isFiller(String instruction) {
        return FILLER.matcher(instruction.strip()).matches();
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

    /**
     * One catalog line for the prompt: key, name, category, the first sentence of its current
     * instructions, the tool servers it has enabled ("none" when it has none), and a note when it
     * is the default. Reads the agent's version from the database.
     */
    private String describe(Agent agent, List<String> servers) {
        String does = "";
        if (agent.getCurrentVersionId() != null) {
            AgentVersion version =
                    versions.findById(agent.getCurrentVersionId()).orElse(null);
            if (version != null) {
                does = firstSentence(version.getSystemPrompt());
            }
        }
        String tools = servers == null || servers.isEmpty() ? "none" : String.join(", ", servers);
        String fallbackNote =
                GeneralEmployee.isFallback(agent) ? " (default: choose when no other agent clearly fits)" : "";
        return "- key: %s, name: %s, category: %s, does: %s, tools: %s%s"
                .formatted(agent.getKey(), agent.getName(), agent.getCategory(), does, tools, fallbackNote);
    }

    /** The first sentence of a prompt on one line, cut to 160 characters; empty when there is none. */
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
