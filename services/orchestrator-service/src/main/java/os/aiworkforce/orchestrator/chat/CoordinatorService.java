package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.schedule.ScheduleParser;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

/**
 * Reads one message from a person and decides what the workforce does about it.
 *
 * <p>Three things can happen to a message with no {@code @mention} in it: it names a time, and
 * becomes a schedule suggestion; it reads as a question about what the workspace has on file, and
 * becomes a document search; or it is work, and is routed to whichever agent or short chain of
 * agents can do it. A mention skips straight to the last of these, addressed to exactly the agents
 * named. Whatever is decided is recorded as messages in the conversation before this method
 * returns - the model call and the knowledge search are the only network calls made inline; the
 * goal itself runs after, on {@link GoalService}'s own executor.
 */
@Service
public class CoordinatorService {

    static final String NEEDS_CHOICE_MESSAGE =
            "I could not tell which agent should take this. Choose one, or mention an agent with @.";
    static final String DOCUMENTS_UNAVAILABLE = "Document search is unavailable right now.";

    private final Conversations conversations;
    private final ChatMessages messages;
    private final Agents agents;
    private final ToolGrants grants;
    private final Goals goals;
    private final GoalService goalService;
    private final ModelRouterPlanner modelPlanner;
    private final KnowledgeClient knowledge;
    private final WorkspaceZoneLookup zones;
    private final SchedulePreviewer schedulePreviewer;

    public CoordinatorService(
            Conversations conversations, ChatMessages messages, Agents agents, ToolGrants grants, Goals goals,
            GoalService goalService, ModelRouterPlanner modelPlanner, KnowledgeClient knowledge,
            WorkspaceZoneLookup zones, SchedulePreviewer schedulePreviewer) {
        this.conversations = conversations;
        this.messages = messages;
        this.agents = agents;
        this.grants = grants;
        this.goals = goals;
        this.goalService = goalService;
        this.modelPlanner = modelPlanner;
        this.knowledge = knowledge;
        this.zones = zones;
        this.schedulePreviewer = schedulePreviewer;
    }

    private record Step(Agent agent, String instruction) {}

    /** One agent addressed directly, with the whole instruction it is asked to do. */
    @Transactional
    public List<ChatMessage> handleMessage(
            UUID orgId, UUID conversationId, String text, List<UUID> explicitAgentIds, String authorizationHeader) {
        Conversation conversation = conversations.findByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        List<Agent> workspaceAgents = agents.findByOrgIdOrderByName(orgId);

        List<ChatMessage> created = new ArrayList<>();
        UUID requesterId = requesterIdFromContext();

        created.add(append(conversation, "user", requesterId, null, "text", text, Map.of(), null));
        if (conversation.getTitle().isBlank()) {
            conversation.setTitle(truncateAtWord(text, 60));
        }

        MentionParser.Result mention = MentionParser.parse(text, workspaceAgents);
        List<Agent> chosen = new ArrayList<>(mention.agents());
        if (explicitAgentIds != null) {
            for (UUID agentId : explicitAgentIds) {
                agents.findByIdAndOrgId(agentId, orgId).ifPresent(agent -> {
                    if (chosen.stream().noneMatch(a -> a.getId().equals(agent.getId()))) {
                        chosen.add(agent);
                    }
                });
            }
        }

        if (!chosen.isEmpty()) {
            String instruction = mention.text().isBlank() ? text : mention.text();
            List<Step> steps = chosen.stream().map(agent -> new Step(agent, instruction)).toList();
            routeAsWork(orgId, conversation, requesterId, created, text, steps, "mention", List.of(), mentionReason(chosen));
            conversations.save(conversation);
            return created;
        }

        ZoneId zone = zones.zoneFor(orgId);
        Instant now = Instant.now();
        IntentDetector.Intent intent = IntentDetector.detect(text, zone, now, schedulePreviewer);
        switch (intent) {
            case SCHEDULE -> handleSchedule(orgId, conversation, created, workspaceAgents, text, zone, now);
            case DOCUMENTS -> handleDocuments(orgId, conversation, created, text, authorizationHeader);
            case WORK -> handleWork(orgId, conversation, requesterId, created, workspaceAgents, text);
        }
        conversations.save(conversation);
        return created;
    }

    private void handleWork(
            UUID orgId, Conversation conversation, UUID requesterId, List<ChatMessage> created,
            List<Agent> workspaceAgents, String text) {
        Optional<ModelRouterPlanner.Plan> modelPlan = modelPlanner.plan(orgId, text, workspaceAgents);
        if (modelPlan.isPresent()) {
            List<Step> steps = modelPlan.get().steps().stream()
                    .map(step -> new Step(agentById(workspaceAgents, step.agentId()), step.instruction()))
                    .toList();
            if (steps.stream().noneMatch(step -> step.agent() == null)) {
                routeAsWork(orgId, conversation, requesterId, created, text, steps, "model", List.of(), modelPlan.get().reason());
                return;
            }
        }

        Map<UUID, List<String>> agentServers = toolServersByAgent(workspaceAgents);
        RuleRouter.Result ruleResult = RuleRouter.route(text, workspaceAgents, agentServers);
        if (!ruleResult.needsChoice()) {
            List<Step> steps = ruleResult.steps().stream().map(r -> new Step(r.agent(), r.instruction())).toList();
            routeAsWork(orgId, conversation, requesterId, created, text, steps, "rules",
                    flattenMatched(ruleResult.steps()), ruleReason(ruleResult.steps()));
            return;
        }
        created.add(appendNeedsChoice(conversation, ruleResult.alternatives()));
    }

    private void handleSchedule(
            UUID orgId, Conversation conversation, List<ChatMessage> created, List<Agent> workspaceAgents,
            String text, ZoneId zone, Instant now) {
        ParsedSchedule parsed = schedulePreviewer.tryParse(text, zone, now).orElse(null);
        if (parsed == null) {
            handleWork(orgId, conversation, requesterIdFromContext(), created, workspaceAgents, text);
            return;
        }
        String instruction = IntentDetector.withoutTimingPhrase(text);

        Agent agent;
        String agentInstruction;
        Optional<ModelRouterPlanner.Plan> modelPlan = modelPlanner.plan(orgId, instruction, workspaceAgents);
        if (modelPlan.isPresent() && !modelPlan.get().steps().isEmpty()) {
            ModelRouterPlanner.PlannedStep first = modelPlan.get().steps().getFirst();
            agent = agentById(workspaceAgents, first.agentId());
            agentInstruction = first.instruction();
        } else {
            Map<UUID, List<String>> agentServers = toolServersByAgent(workspaceAgents);
            RuleRouter.Result ruleResult = RuleRouter.route(instruction, workspaceAgents, agentServers);
            if (ruleResult.needsChoice()) {
                created.add(appendNeedsChoice(conversation, ruleResult.alternatives()));
                return;
            }
            RuleRouter.Routed first = ruleResult.steps().getFirst();
            agent = first.agent();
            agentInstruction = first.instruction();
        }
        if (agent == null) {
            handleWork(orgId, conversation, requesterIdFromContext(), created, workspaceAgents, text);
            return;
        }

        List<String> nextRuns = ScheduleParser.nextRuns(parsed, zone, now, 5).stream().map(Instant::toString).toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("text", text);
        detail.put("kind", parsed.kind());
        detail.put("cron", parsed.cron());
        detail.put("runAt", parsed.runAt() == null ? null : parsed.runAt().toString());
        detail.put("description", parsed.description());
        detail.put("timezone", zone.getId());
        detail.put("nextRuns", nextRuns);
        detail.put("agentId", agent.getId().toString());
        detail.put("agentName", agent.getName());
        detail.put("instruction", agentInstruction);
        detail.put("name", truncateAtWord(agentInstruction, 60));

        String content = "A schedule for " + agent.getName() + ": " + parsed.description();
        created.add(append(conversation, "coordinator", null, null, "schedule_suggestion", content, detail, null));
    }

    private void handleDocuments(
            UUID orgId, Conversation conversation, List<ChatMessage> created, String text, String authorizationHeader) {
        Optional<KnowledgeClient.SearchResult> result = knowledge.search(orgId, text, authorizationHeader);
        if (result.isEmpty()) {
            created.add(append(conversation, "coordinator", null, null, "error", DOCUMENTS_UNAVAILABLE,
                    Map.of("reason", DOCUMENTS_UNAVAILABLE), null));
            return;
        }
        KnowledgeClient.SearchResult search = result.get();
        List<Map<String, Object>> passages = search.passages().stream().map(this::passageDetail).toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("query", text);
        detail.put("grounded", search.grounded());
        detail.put("passages", passages);
        String content = search.grounded()
                ? "Found " + passages.size() + " passage(s) that may help."
                : "No document on file supports an answer to this.";
        created.add(append(conversation, "coordinator", null, null, "documents", content, detail, null));
    }

    private Map<String, Object> passageDetail(KnowledgeClient.Passage passage) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("chunkId", passage.chunkId().toString());
        detail.put("documentId", passage.documentId().toString());
        detail.put("documentTitle", passage.documentTitle());
        detail.put("uri", passage.uri());
        detail.put("pageNumber", passage.pageNumber());
        detail.put("heading", passage.heading());
        detail.put("content", passage.content());
        detail.put("score", passage.score());
        return detail;
    }

    /**
     * Creates the goal this routing decided on, and records the routing and progress messages for
     * it - or, when {@link GoalService} refuses the chain (too many steps, an agent repeated too
     * often), records that refusal as an honest error message instead of failing the whole request
     * and losing the person's own message with it.
     */
    private void routeAsWork(
            UUID orgId, Conversation conversation, UUID requesterId, List<ChatMessage> created, String text,
            List<Step> steps, String mode, List<String> matched, String reason) {
        List<GoalService.NewTask> tasks = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            tasks.add(new GoalService.NewTask(
                    step.agent().getId(), truncateAtWord(step.instruction(), 60), step.instruction(),
                    i == 0 ? List.of() : List.of(i - 1)));
        }
        GoalService.NewGoal spec = new GoalService.NewGoal(
                truncateAtWord(text, 80), text, requesterId, "chat", conversation.getId(), null, tasks);
        Goal goal;
        try {
            goal = goalService.createGoal(orgId, spec, true);
        } catch (ApiException refused) {
            // A validation refusal's readable text is in its "problem" detail, not its message -
            // ApiException.validation() always carries the same generic message on its own.
            Object problem = refused.details().get("problem");
            String errorContent = problem instanceof String problemText ? problemText : refused.getMessage();
            created.add(append(conversation, "coordinator", null, null, "error", errorContent,
                    Map.of("reason", errorContent), null));
            return;
        }

        List<Map<String, Object>> agentsDetail = steps.stream().map(step -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", step.agent().getId().toString());
            m.put("name", step.agent().getName());
            m.put("instruction", step.instruction());
            return m;
        }).toList();
        Map<String, Object> routingDetail = new LinkedHashMap<>();
        routingDetail.put("mode", mode);
        routingDetail.put("agents", agentsDetail);
        routingDetail.put("reason", reason);
        routingDetail.put("matched", matched);
        routingDetail.put("alternatives", List.of());
        routingDetail.put("needsChoice", false);
        created.add(append(conversation, "coordinator", null, null, "routing", reason, routingDetail, goal.getId()));

        String progressContent = steps.size() == 1
                ? "Working with " + steps.getFirst().agent().getName() + "."
                : "Working through " + steps.size() + " step(s): "
                        + steps.stream().map(step -> step.agent().getName()).collect(Collectors.joining(" → ")) + ".";
        created.add(append(conversation, "coordinator", null, null, "progress", progressContent,
                Map.of("goalId", goal.getId().toString()), goal.getId()));
    }

    private ChatMessage appendNeedsChoice(Conversation conversation, List<RuleRouter.ScoredAgent> alternatives) {
        List<Map<String, Object>> altDetail = alternatives.stream().map(scored -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", scored.agent().getId().toString());
            m.put("name", scored.agent().getName());
            m.put("score", scored.score());
            return m;
        }).toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", "rules");
        detail.put("agents", List.of());
        detail.put("reason", NEEDS_CHOICE_MESSAGE);
        detail.put("matched", List.of());
        detail.put("alternatives", altDetail);
        detail.put("needsChoice", true);
        return append(conversation, "coordinator", null, null, "routing", NEEDS_CHOICE_MESSAGE, detail, null);
    }

    /**
     * Reroutes a routing message: cancels its goal if it is still active, and starts a new one for
     * the chosen agent with the same original instruction.
     */
    @Transactional
    public List<ChatMessage> reroute(UUID orgId, UUID conversationId, UUID messageId, UUID chosenAgentId) {
        Conversation conversation = conversations.findByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        ChatMessage target = messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> ApiException.notFound("message", messageId));
        if (!"routing".equals(target.getKind())) {
            throw ApiException.validation("messageId", "Only a routing message can be rerouted.");
        }
        Agent agent = agents.findByIdAndOrgId(chosenAgentId, orgId)
                .orElseThrow(() -> ApiException.notFound("agent", chosenAgentId));

        if (target.getGoalId() != null) {
            goals.findByIdAndOrgId(target.getGoalId(), orgId)
                    .filter(goal -> !goal.isFinished())
                    .ifPresent(goal -> goalService.cancel(orgId, goal.getId()));
        }

        String instruction = originalInstructionFor(conversationId, target);
        List<ChatMessage> created = new ArrayList<>();
        UUID requesterId = requesterIdFromContext();
        routeAsWork(orgId, conversation, requesterId, created, instruction,
                List.of(new Step(agent, instruction)), "manual", List.of(), "You chose " + agent.getName() + ".");
        conversations.save(conversation);
        return created;
    }

    /**
     * The instruction the routing message answered - the user message immediately before it. Every
     * routing message is appended straight after the user message that produced it, so its own
     * position minus one is always that message, unless the conversation's first message was
     * somehow itself a routing message, which never happens.
     */
    private String originalInstructionFor(UUID conversationId, ChatMessage routingMessage) {
        if (routingMessage.getPosition() == 0) {
            return routingMessage.getContent();
        }
        List<ChatMessage> ordered = messages.findByConversationIdOrderByPosition(conversationId);
        for (ChatMessage candidate : ordered) {
            if (candidate.getPosition() == routingMessage.getPosition() - 1) {
                return candidate.getContent();
            }
        }
        return routingMessage.getContent();
    }

    private ChatMessage append(
            Conversation conversation, String authorKind, UUID authorId, UUID agentId, String kind,
            String content, Map<String, Object> detail, UUID goalId) {
        int position = conversation.nextPosition();
        conversation.recordMessage(truncateAtWord(content, 120));
        return messages.save(ChatMessage.of(
                conversation.getOrgId(), conversation.getId(), position, authorKind, authorId, agentId,
                kind, content, detail, goalId));
    }

    private Map<UUID, List<String>> toolServersByAgent(List<Agent> workspaceAgents) {
        Map<UUID, List<String>> byAgent = new LinkedHashMap<>();
        for (Agent agent : workspaceAgents) {
            byAgent.put(agent.getId(), grants.findByAgentIdAndEnabledTrue(agent.getId()).stream()
                    .map(AgentToolGrant::getServer)
                    .distinct()
                    .toList());
        }
        return byAgent;
    }

    private static Agent agentById(List<Agent> agents, UUID agentId) {
        return agents.stream().filter(agent -> agent.getId().equals(agentId)).findFirst().orElse(null);
    }

    private static String mentionReason(List<Agent> chosen) {
        if (chosen.size() == 1) {
            return "You mentioned @" + chosen.getFirst().getName() + ".";
        }
        return "You mentioned " + chosen.stream().map(a -> "@" + a.getName()).collect(Collectors.joining(" and ")) + ".";
    }

    private static String ruleReason(List<RuleRouter.Routed> steps) {
        return steps.stream()
                .map(step -> {
                    String words = step.matched().stream().map(w -> "\"" + w + "\"").collect(Collectors.joining(", "));
                    return "Matched " + words + " in " + step.agent().getName() + "'s work.";
                })
                .collect(Collectors.joining(" "));
    }

    private static List<String> flattenMatched(List<RuleRouter.Routed> steps) {
        List<String> matched = new ArrayList<>();
        for (RuleRouter.Routed step : steps) {
            for (String word : step.matched()) {
                if (!matched.contains(word)) {
                    matched.add(word);
                }
            }
        }
        return matched;
    }

    /** The acting person, when the actor is a real signed-in identity rather than the platform itself. */
    private static UUID requesterIdFromContext() {
        return RequestContext.actor().map(actor -> parseUuidOrNull(actor.id())).orElse(null);
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }

    /** Truncated at the last whole word that fits, so a title never ends mid-word. */
    static String truncateAtWord(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= max) {
            return stripped;
        }
        int cut = stripped.lastIndexOf(' ', max - 1);
        String head = cut > 0 ? stripped.substring(0, cut) : stripped.substring(0, max);
        return head.strip();
    }
}
