package os.aiworkforce.orchestrator.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.schedule.ScheduleParser;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Reads one message from a person and decides what the workforce does about it.
 *
 * <p>{@link #handleMessage} runs in three phases, never in one transaction (0.2, A3.2): it records
 * what the person said first, so that write can never be lost to anything that happens afterwards;
 * decides what to do with no transaction open, since a model call or a document search can each
 * take many seconds; then acts on that decision and records the replies, in a transaction of its
 * own. A refusal in the last phase - a goal the engine will not accept - still reaches the person
 * as an honest sentence, appended in a further transaction of its own once the failed one has
 * rolled back.
 *
 * <p>Three things can happen to a message with no {@code @mention} in it: it names a time, and
 * becomes a schedule suggestion; it reads as a question about what the workspace has on file, and
 * becomes a document search; or it is work, and is routed to whichever agent or short chain of
 * agents can do it - and when nothing in the workspace fits, to the workspace's General Employee
 * rather than a dead end. A mention skips straight to routing, addressed to exactly the agents
 * named, unless one of them is paused, in which case nothing starts and a choice is offered instead.
 *
 * <p>Whoever takes a piece of work is given what the person wrote, as they wrote it: the planner
 * and the rules only choose who works and name each part, and a chain's steps each get the whole
 * request ahead of their own part. In front of it go the last answer in the conversation, in full,
 * so that "now send it" has something to send, and the passages from the workspace's documents
 * that bear on it - searched for every request that is work or names an agent, and recorded on the
 * routing message so the answer can show them as its sources.
 */
@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);

    static final String DOCUMENTS_UNAVAILABLE = "Document search is unavailable right now.";
    static final String TIE_MESSAGE = "Two agents fit this equally well. Choose one.";
    static final String NO_MATCH_MESSAGE =
            "No specialist matched this and General Employee is paused. Choose an agent, or mention one with @.";
    static final String NO_AGENTS_MESSAGE =
            "This workspace has no active agents. Add one in Agents, or resume General Employee.";

    /**
     * What a first step's instruction may come to once the person's request (up to 10,000
     * characters, kept whole), their last answer (6,000), the document passages (about 6,000) and
     * the older turns are all in it. Only the older turns give way to it.
     */
    static final int MAX_INSTRUCTION_CHARS = 25_000;
    /** The most earlier turns fetched for a request; the thread keeps a handful of them. */
    private static final int EARLIER_TURNS_WINDOW = 30;
    /** What an agent is told when the request leans on documents and the search found none that cover it. */
    public static final String NO_COVERAGE_NOTE =
            "No workspace document covers this request; say so rather than stating company facts.";
    /**
     * What a documents message says about passages it does not show: the conversation is readable by
     * the whole workspace, and a restricted source is not.
     */
    static final String RESTRICTED_NOTE =
            " Some are in restricted documents, which only people who manage knowledge can read; search them in Knowledge.";
    private static final int MAX_COLLEAGUES = 6;
    private static final int MAX_COLLEAGUE_SUMMARY_CHARS = 120;
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?=\\s|$)");

    private final Conversations conversations;
    private final ChatMessages messages;
    private final Agents agents;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final Goals goals;
    private final GoalService goalService;
    private final ModelRouterPlanner modelPlanner;
    private final KnowledgeClient knowledge;
    private final WorkspaceZoneLookup zones;
    private final SchedulePreviewer schedulePreviewer;
    private final GeneralEmployee generalEmployee;
    private final ChatAppender appender;
    private final TransactionTemplate tx;

    /** Who may read which conversation; absent only where a test builds this service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationAccess access;

    /** Files attached to a message; absent only where a test builds this service by hand. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AttachmentService attachmentService;

    /** The conversation, when the caller may read it; otherwise "not found", so a private thread is not confirmed. */
    private Conversation visible(UUID orgId, UUID conversationId) {
        Conversation conversation = conversations
                .findByIdAndOrgId(conversationId, orgId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        requireReadable(conversation);
        return conversation;
    }

    private void requireReadable(Conversation conversation) {
        if (access != null && !access.canRead(conversation, RequestContext.requireActor())) {
            throw ApiException.notFound("conversation", conversation.getId());
        }
    }

    public CoordinatorService(
            Conversations conversations,
            ChatMessages messages,
            Agents agents,
            AgentVersions versions,
            ToolGrants grants,
            Goals goals,
            GoalService goalService,
            ModelRouterPlanner modelPlanner,
            KnowledgeClient knowledge,
            WorkspaceZoneLookup zones,
            SchedulePreviewer schedulePreviewer,
            GeneralEmployee generalEmployee,
            ChatAppender appender,
            PlatformTransactionManager transactionManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.agents = agents;
        this.versions = versions;
        this.grants = grants;
        this.goals = goals;
        this.goalService = goalService;
        this.modelPlanner = modelPlanner;
        this.knowledge = knowledge;
        this.zones = zones;
        this.schedulePreviewer = schedulePreviewer;
        this.generalEmployee = generalEmployee;
        this.appender = appender;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * One agent's share of a request. {@code part} is the coordinator's short label for it - the
     * planner's sentence, a clause the rules matched, or the message itself - and names the task;
     * what the agent reads is the person's own request, built in {@link #buildWorkDecision}.
     */
    private record Step(Agent agent, String part, boolean fallback) {}

    /**
     * What goes ahead of a request when an agent reads it: the conversation so far and the
     * passages from the workspace's documents that bear on it.
     *
     * @param history the earlier turns of the conversation
     * @param passages exactly the passages the agent is given - at most {@value #MAX_KNOWLEDGE_PASSAGES},
     *     in the order it reads them, with the text it reads - and the ones shown as its sources
     * @param uncovered whether the request leans on the workspace's documents and none covers it
     */
    record Background(ThreadContext.History history, List<Map<String, Object>> passages, boolean uncovered) {

        static Background of(ThreadContext.History history) {
            return new Background(history, List.of(), false);
        }

        Background withPassages(List<Map<String, Object>> used) {
            return new Background(history, List.copyOf(used), false);
        }

        Background withoutCoverage() {
            return new Background(history, List.of(), true);
        }

        boolean isEmpty() {
            return history.isEmpty() && passages.isEmpty() && !uncovered;
        }
    }

    /** The goal id and the task a stop or retry from chat leaves it at. */
    public record GoalActionResult(UUID goalId, String status, UUID fromTaskId) {}

    // ---- Decisions --------------------------------------------------------------------------

    private sealed interface Decision
            permits WorkDecision, ChoiceDecision, ScheduleDecision, DocumentsDecision, ErrorDecision {}

    private record WorkDecision(
            GoalService.NewGoal spec,
            List<Step> steps,
            String mode,
            List<String> matched,
            String reason,
            List<Map<String, Object>> alternatives,
            String requestText,
            List<Map<String, Object>> passages)
            implements Decision {}

    private record ChoiceDecision(String reason, List<Map<String, Object>> alternatives, String requestText)
            implements Decision {}

    private record ScheduleDecision(String content, Map<String, Object> detail) implements Decision {}

    private record DocumentsDecision(String content, Map<String, Object> detail) implements Decision {}

    /**
     * Something the coordinator could not do. {@code requestText} is what the person asked, kept on
     * the error message so "Try again" can put the request back in the box rather than the error.
     */
    private record ErrorDecision(String content, String requestText) implements Decision {}

    // ---- Sending a message --------------------------------------------------------------------

    public List<ChatMessage> handleMessage(
            UUID orgId, UUID conversationId, String text, List<UUID> explicitAgentIds, String authorizationHeader) {
        return handleMessage(orgId, conversationId, text, explicitAgentIds, List.of(), authorizationHeader);
    }

    /**
     * As {@link #handleMessage(UUID, UUID, String, List, String)}, with files attached. The files are
     * checked and tied to the message as it is written; the work it starts is routed knowing their
     * names, and is given them (see {@link AttachmentPrompt}). A message with files is always work
     * for an agent - never a schedule or a search of the workspace's documents - because the files
     * are what it is about, and only an agent's run reads them.
     */
    public List<ChatMessage> handleMessage(
            UUID orgId,
            UUID conversationId,
            String typed,
            List<UUID> explicitAgentIds,
            List<UUID> attachmentIds,
            String authorizationHeader) {
        generalEmployee.ensure(orgId);
        UUID requesterId = requesterIdFromContext();
        String written = typed == null ? "" : typed;
        List<ChatAttachments.Row> files = new ArrayList<>();

        // Phase 1: record what the person said, so it is never lost to anything that follows.
        ChatMessage userMessage = inTransaction(() -> {
            Conversation c = appender.lock(orgId, conversationId)
                    .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
            requireReadable(c);
            if (attachmentService != null && attachmentIds != null && !attachmentIds.isEmpty()) {
                files.addAll(attachmentService.checkForSend(
                        orgId, RequestContext.requireActor(), conversationId, attachmentIds));
            }
            if (written.isBlank() && files.isEmpty()) {
                throw ApiException.validation("text", "Write a message or attach a file.");
            }
            Map<String, Object> detail = files.isEmpty()
                    ? Map.of()
                    : Map.of("attachments", AttachmentService.detailOf(files));
            ChatMessage m = appender.append(c, "user", requesterId, null, "text", written, detail, null);
            if (!files.isEmpty()) {
                // Written now rather than at commit: the files are tied to the message by a key the
                // database checks, so the message row must exist before the update that names it.
                messages.flush();
                attachmentService.bind(orgId, conversationId, m.getId(), files);
            }
            if (c.getTitle().isBlank()) {
                c.setTitle(truncateAtWord(written.isBlank() ? files.getFirst().name() : written, 60));
            }
            return m;
        });
        String text = AttachmentService.requestWithNames(written, files);
        boolean withFiles = !files.isEmpty();

        // Phase 2: decide, with no transaction open - the planner can take up to 20 seconds and a
        // document search up to 10.
        Decision decision;
        try {
            decision = decide(
                    orgId,
                    conversationId,
                    userMessage,
                    text,
                    explicitAgentIds,
                    authorizationHeader,
                    requesterId,
                    withFiles);
        } catch (RuntimeException e) {
            log.warn("Could not decide what to do with a chat message in conversation {}", conversationId, e);
            decision = new ErrorDecision(
                    "The coordinator could not decide who takes this. Try again, or mention an agent with @.", text);
        }

        // Phase 3: act on the decision and record the replies.
        List<ChatMessage> created = new ArrayList<>(List.of(userMessage));
        Decision toApply = decision;
        try {
            created.addAll(inTransaction(() -> {
                List<ChatMessage> replies = apply(orgId, conversationId, toApply);
                if (withFiles) {
                    // In the transaction that creates the goal: its first run starts only once this
                    // commits, so it always finds its files.
                    replies.stream()
                            .map(ChatMessage::getGoalId)
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .ifPresent(goalId -> attachmentService.linkGoal(orgId, userMessage.getId(), goalId));
                }
                return replies;
            }));
        } catch (RuntimeException e) {
            log.error("Could not act on a chat message in conversation {}", conversationId, e);
            String problem = problemMessage(e);
            created.addAll(inTransaction(
                    () -> applySimple(orgId, conversationId, "error", problem, errorDetail(problem, text))));
        }
        return created;
    }

    private <T> T inTransaction(Supplier<T> body) {
        return tx.execute(status -> body.get());
    }

    // ---- Deciding ------------------------------------------------------------------------------

    private Decision decide(
            UUID orgId,
            UUID conversationId,
            ChatMessage userMessage,
            String text,
            List<UUID> explicitAgentIds,
            String authorizationHeader,
            UUID requesterId,
            boolean withFiles) {
        List<Agent> workspaceAgents = agents.findByOrgIdOrderByName(orgId);
        Agent fallback = generalEmployee.activeIn(workspaceAgents).orElse(null);
        Actor actor = RequestContext.actor().orElse(null);

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

        List<ChatMessage> chronological = earlierMessages(conversationId, userMessage);
        Background background = Background.of(
                ThreadContext.of(chronological, nameMap(workspaceAgents), Map.of(), requesterId));

        if (!chosen.isEmpty()) {
            Agent paused =
                    chosen.stream().filter(a -> !a.isActive()).findFirst().orElse(null);
            if (paused != null) {
                String reason = paused.getName()
                        + " is paused, so nothing was started. Resume it in Agents, or choose someone else.";
                return new ChoiceDecision(reason, pausedMentionAlternatives(fallback, workspaceAgents), text);
            }
            // The label for each agent's task; what each reads is the message as it was written.
            String part = mention.text().isBlank() ? text : mention.text();
            List<Step> steps =
                    chosen.stream().map(agent -> new Step(agent, part, false)).toList();
            return buildWorkDecision(
                    workspaceAgents,
                    requesterId,
                    conversationId,
                    steps,
                    "mention",
                    List.of(),
                    mentionReason(chosen),
                    List.of(),
                    text,
                    withDocuments(background, orgId, part, chronological, authorizationHeader));
        }

        if (withFiles) {
            return decideWork(
                    orgId,
                    workspaceAgents,
                    fallback,
                    requesterId,
                    conversationId,
                    text,
                    chronological,
                    withDocuments(background, orgId, text, chronological, authorizationHeader));
        }

        ZoneId zone = zones.zoneFor(orgId);
        Instant now = Instant.now();
        IntentDetector.Intent intent = IntentDetector.detect(text, zone, now, schedulePreviewer);
        return switch (intent) {
            case SCHEDULE ->
                decideSchedule(
                        orgId,
                        conversationId,
                        workspaceAgents,
                        fallback,
                        requesterId,
                        text,
                        zone,
                        now,
                        chronological,
                        authorizationHeader,
                        background);
            case DOCUMENTS ->
                decideDocuments(
                        orgId,
                        conversationId,
                        workspaceAgents,
                        fallback,
                        requesterId,
                        actor,
                        text,
                        authorizationHeader,
                        chronological,
                        background);
            case WORK ->
                looksInformational(text)
                        ? decideDocuments(
                                orgId,
                                conversationId,
                                workspaceAgents,
                                fallback,
                                requesterId,
                                actor,
                                text,
                                authorizationHeader,
                                chronological,
                                background)
                        : decideWork(
                                orgId,
                                workspaceAgents,
                                fallback,
                                requesterId,
                                conversationId,
                                text,
                                chronological,
                                withDocuments(background, orgId, text, chronological, authorizationHeader));
        };
    }

    private Decision decideWork(
            UUID orgId,
            List<Agent> workspaceAgents,
            Agent fallback,
            UUID requesterId,
            UUID conversationId,
            String text,
            List<ChatMessage> chronological,
            Background background) {
        ModelRouterPlanner.PlanHints hints =
                new ModelRouterPlanner.PlanHints(lastAnswerAgent(chronological, workspaceAgents));

        Optional<ModelRouterPlanner.Plan> modelPlan = modelPlanner.plan(orgId, text, workspaceAgents, hints);
        if (modelPlan.isPresent()) {
            List<Step> steps = modelPlan.get().steps().stream()
                    .map(step -> new Step(agentById(workspaceAgents, step.agentId()), step.instruction(), false))
                    .toList();
            if (steps.stream().noneMatch(step -> step.agent() == null)) {
                return buildWorkDecision(
                        workspaceAgents,
                        requesterId,
                        conversationId,
                        steps,
                        "model",
                        List.of(),
                        modelPlan.get().reason(),
                        List.of(),
                        text,
                        background);
            }
        }

        Map<UUID, List<String>> agentServers = toolServersByAgent(workspaceAgents);
        RuleRouter.Result result = RuleRouter.route(text, workspaceAgents, agentServers, fallback);
        if (result.needsChoice()) {
            return new ChoiceDecision(choiceReasonFor(result.reason()), scoredList(result.alternatives()), text);
        }
        List<Step> steps = result.steps().stream()
                .map(r -> new Step(r.agent(), r.instruction(), r.fallback()))
                .toList();
        if (result.allFallback()) {
            // Without a model to read the thread, a short follow-up such as "make it shorter" matches
            // nobody's keywords. It belongs to whoever gave the last answer, not to General Employee.
            Agent previous = lastAnswerAgent(chronological, workspaceAgents);
            if (previous != null
                    && !previous.isFallback()
                    && "active".equals(previous.getStatus())
                    && looksLikeFollowUp(text)) {
                return buildWorkDecision(
                        workspaceAgents,
                        requesterId,
                        conversationId,
                        List.of(new Step(previous, text, false)),
                        "rules",
                        List.of(),
                        "This follows on from " + previous.getName() + "'s last answer, so " + previous.getName()
                                + " is taking it.",
                        scoredList(result.alternatives()),
                        text,
                        background);
            }
            return buildWorkDecision(
                    workspaceAgents,
                    requesterId,
                    conversationId,
                    steps,
                    "fallback",
                    List.of(),
                    "No specialist matched this, so General Employee is taking it.",
                    scoredList(result.alternatives()),
                    text,
                    background);
        }
        return buildWorkDecision(
                workspaceAgents,
                requesterId,
                conversationId,
                steps,
                "rules",
                flattenMatched(result.steps()),
                ruleReason(result.steps()),
                scoredList(result.alternatives()),
                text,
                background);
    }

    private Decision decideSchedule(
            UUID orgId,
            UUID conversationId,
            List<Agent> workspaceAgents,
            Agent fallback,
            UUID requesterId,
            String text,
            ZoneId zone,
            Instant now,
            List<ChatMessage> chronological,
            String authorizationHeader,
            Background background) {
        ParsedSchedule parsed = schedulePreviewer.tryParse(text, zone, now).orElse(null);
        if (parsed == null) {
            return decideWork(
                    orgId,
                    workspaceAgents,
                    fallback,
                    requesterId,
                    conversationId,
                    text,
                    chronological,
                    withDocuments(background, orgId, text, chronological, authorizationHeader));
        }
        // What every run of the schedule is asked to do, exactly as the person wrote it apart from
        // the timing phrase. Whatever the router writes below only names the schedule.
        String instruction = IntentDetector.withoutTimingPhrase(text);

        Agent agent;
        String label;
        Optional<ModelRouterPlanner.Plan> modelPlan = modelPlanner.plan(orgId, instruction, workspaceAgents);
        if (modelPlan.isPresent() && !modelPlan.get().steps().isEmpty()) {
            ModelRouterPlanner.PlannedStep first = modelPlan.get().steps().getFirst();
            agent = agentById(workspaceAgents, first.agentId());
            label = first.instruction();
        } else {
            Map<UUID, List<String>> agentServers = toolServersByAgent(workspaceAgents);
            RuleRouter.Result result = RuleRouter.route(instruction, workspaceAgents, agentServers, fallback);
            if (result.needsChoice()) {
                return new ChoiceDecision(choiceReasonFor(result.reason()), scoredList(result.alternatives()), text);
            }
            RuleRouter.Routed first = result.steps().getFirst();
            agent = first.agent();
            label = first.instruction();
        }
        if (agent == null) {
            return decideWork(
                    orgId,
                    workspaceAgents,
                    fallback,
                    requesterId,
                    conversationId,
                    text,
                    chronological,
                    withDocuments(background, orgId, text, chronological, authorizationHeader));
        }

        List<String> nextRuns = ScheduleParser.nextRuns(parsed, zone, now, 5).stream()
                .map(Instant::toString)
                .toList();
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
        detail.put("instruction", instruction);
        detail.put("name", truncateAtWord(label, 60));

        String content = "A schedule for " + agent.getName() + ": " + parsed.description();
        return new ScheduleDecision(content, detail);
    }

    /**
     * Answers a question about the workspace's documents: the passages that bear on it, quoted,
     * when the person can only read; otherwise the request goes to whichever agent takes it, with
     * those passages put in front of it, so the person gets an answer that cites them.
     */
    private Decision decideDocuments(
            UUID orgId,
            UUID conversationId,
            List<Agent> workspaceAgents,
            Agent fallback,
            UUID requesterId,
            Actor actor,
            String text,
            String authorizationHeader,
            List<ChatMessage> chronological,
            Background background) {
        boolean canFallback = fallback != null && actor != null && actor.hasPermission(Permission.Codes.TASK_CREATE);
        if (canFallback && PassageRelevance.isConversational(text)) {
            return decideWork(
                    orgId, workspaceAgents, fallback, requesterId, conversationId, text, chronological, background);
        }
        String query = searchQueryFor(text, chronological);
        Optional<KnowledgeClient.SearchResult> result = knowledge.search(orgId, query, authorizationHeader);
        if (result.isEmpty()) {
            // Search is down: treat the question as ordinary work, so the best agent answers it.
            if (canFallback) {
                return decideWork(
                        orgId, workspaceAgents, fallback, requesterId, conversationId, text, chronological, background);
            }
            return new ErrorDecision(DOCUMENTS_UNAVAILABLE, text);
        }
        KnowledgeClient.SearchResult search = result.get();
        if (canFallback) {
            // Either the documents answer it, and the passages go to whoever takes the request, or
            // none does and it is a question for the workforce like any other - so "how many
            // customers do we have" can reach Customer Support rather than always landing on General
            // Employee.
            return decideWork(
                    orgId,
                    workspaceAgents,
                    fallback,
                    requesterId,
                    conversationId,
                    text,
                    chronological,
                    documentsBackground(background, text, query, search));
        }
        // What is stored is read by everyone in the conversation, so a passage from a restricted
        // source is counted here and never kept: its text and its title stay with the people who
        // may search that source.
        List<KnowledgeClient.Passage> relevant = PassageRelevance.relevant(query, search.passages());
        List<Map<String, Object>> passages = relevant.stream()
                .filter(passage -> !passage.restricted())
                .map(passage -> passageDetail(passage, Integer.MAX_VALUE))
                .toList();
        int hidden = relevant.size() - passages.size();
        boolean grounded = search.grounded() && !passages.isEmpty();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("query", text);
        detail.put("grounded", grounded);
        detail.put("passages", passages);
        if (search.degraded()) {
            detail.put("degraded", true);
        }
        String content;
        if (grounded) {
            content = "Found " + passages.size() + " passage(s) that may help." + (hidden > 0 ? RESTRICTED_NOTE : "");
        } else if (search.grounded() && hidden > 0) {
            content = "Found " + hidden + " passage(s) in restricted documents." + RESTRICTED_NOTE;
        } else {
            content = "No document on file supports an answer to this.";
        }
        return new DocumentsDecision(content, detail);
    }

    /**
     * The background with the workspace's documents searched for what bears on a piece of work, so
     * that an agent given "Draft a reply using our refund policy" reads the policy rather than
     * inventing one. A search that cannot run leaves the work to go ahead without passages.
     */
    private Background withDocuments(
            Background background, UUID orgId, String text, List<ChatMessage> chronological, String authorization) {
        if (PassageRelevance.isConversational(text)) {
            // About the agent, a greeting or thanks: no document is what it asks for.
            return background;
        }
        String query = searchQueryFor(text, chronological);
        Optional<KnowledgeClient.SearchResult> result = knowledge.search(orgId, query, authorization);
        return result.map(search -> documentsBackground(background, text, query, search))
                .orElse(background);
    }

    /**
     * What a search adds to the background: the passages when it was grounded, otherwise - when the
     * request leans on documents - the note that none covers it, so the agent says so rather than
     * stating company facts it would have to make up.
     */
    private static Background documentsBackground(
            Background background, String text, String query, KnowledgeClient.SearchResult search) {
        List<KnowledgeClient.Passage> relevant = search.grounded() ? PassageRelevance.relevant(query, search.passages()) : List.of();
        if (!relevant.isEmpty()) {
            return background.withPassages(usedPassages(relevant));
        }
        return IntentDetector.refersToDocuments(text) ? background.withoutCoverage() : background;
    }

    /**
     * The passages an agent is given: the best few, with the text it reads. One list, shared with
     * {@link #withKnowledge}, so what is recorded as its sources is what it was given.
     */
    private static List<Map<String, Object>> usedPassages(List<KnowledgeClient.Passage> found) {
        return found.stream()
                .limit(MAX_KNOWLEDGE_PASSAGES)
                .map(passage -> passageDetail(passage, MAX_KNOWLEDGE_PASSAGE_CHARS))
                .toList();
    }

    /** Builds and validates the goal a piece of routed work would become, without creating it yet. */
    private Decision buildWorkDecision(
            List<Agent> workspaceAgents,
            UUID requesterId,
            UUID conversationId,
            List<Step> steps,
            String mode,
            List<String> matched,
            String reason,
            List<Map<String, Object>> alternatives,
            String requestText,
            Background background) {
        // Every agent reads what the person wrote. The planner only chooses who does what, so its
        // sentence is a label: for a chain it says which part of the request is this agent's.
        boolean labelledParts = steps.size() > 1 && !"mention".equals(mode);
        List<GoalService.NewTask> tasks = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String body = labelledParts ? GoalService.chainStepInstruction(requestText, step.part()) : requestText;
            String instruction = i == 0 ? withPreamble(background, step.agent().getId(), body) : body;
            if (step.fallback() || step.agent().isFallback()) {
                instruction = instruction + colleagueBlock(workspaceAgents, step.agent());
            }
            tasks.add(new GoalService.NewTask(
                    step.agent().getId(),
                    truncateAtWord(step.part(), 60),
                    instruction,
                    i == 0 ? List.of() : List.of(i - 1)));
        }
        GoalService.NewGoal spec = new GoalService.NewGoal(
                truncateAtWord(requestText, 80), requestText, requesterId, "chat", conversationId, null, tasks);
        try {
            goalService.validate(spec);
        } catch (ApiException refused) {
            return new ErrorDecision(problemMessage(refused), requestText);
        }
        return new WorkDecision(
                spec,
                steps,
                mode,
                matched,
                foundSentence(background.passages()) + reason,
                alternatives,
                requestText,
                background.passages());
    }

    /**
     * "Found 2 passages in Handbook, Policy. " ahead of a routing reason, or nothing when no passage was used.
     *
     * <p>Read by the whole conversation, so a passage from a restricted source is counted but never
     * named: "and 1 from restricted documents" says nothing about which.
     */
    private static String foundSentence(List<Map<String, Object>> passages) {
        if (passages.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> open = openPassages(passages);
        int hidden = passages.size() - open.size();
        String elsewhere = hidden == 0 ? "" : hidden + " from restricted documents";
        if (open.isEmpty()) {
            return "Found " + hidden + (hidden == 1 ? " passage" : " passages") + " in restricted documents. ";
        }
        String titles = open.stream()
                .map(passage -> String.valueOf(passage.get("documentTitle")))
                .distinct()
                .limit(3)
                .collect(Collectors.joining(", "));
        int used = open.size();
        return "Found " + used + (used == 1 ? " passage" : " passages") + " in " + titles
                + (elsewhere.isEmpty() ? "" : " and " + elsewhere) + ". ";
    }

    /** The passages everyone in the workspace may read: those not from a restricted source. */
    static List<Map<String, Object>> openPassages(List<Map<String, Object>> passages) {
        return passages.stream()
                .filter(passage -> !Boolean.TRUE.equals(passage.get("restricted")))
                .toList();
    }

    // ---- Acting on a decision ------------------------------------------------------------------

    private List<ChatMessage> apply(UUID orgId, UUID conversationId, Decision decision) {
        return switch (decision) {
            case WorkDecision w -> applyWork(orgId, conversationId, w);
            case ChoiceDecision c -> applyChoice(orgId, conversationId, c);
            case ScheduleDecision s ->
                applySimple(orgId, conversationId, "schedule_suggestion", s.content(), s.detail());
            case DocumentsDecision d -> applySimple(orgId, conversationId, "documents", d.content(), d.detail());
            case ErrorDecision e ->
                applySimple(orgId, conversationId, "error", e.content(), errorDetail(e.content(), e.requestText()));
        };
    }

    private List<ChatMessage> applyWork(UUID orgId, UUID conversationId, WorkDecision decision) {
        Goal goal = goalService.createGoal(orgId, decision.spec(), true);
        Conversation c = appender.lock(orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));

        List<Map<String, Object>> agentsDetail = decision.steps().stream()
                .map(step -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", step.agent().getId().toString());
                    m.put("name", step.agent().getName());
                    m.put("instruction", step.part());
                    return m;
                })
                .toList();
        Map<String, Object> routingDetail = new LinkedHashMap<>();
        routingDetail.put("mode", decision.mode());
        routingDetail.put("agents", agentsDetail);
        routingDetail.put("reason", decision.reason());
        routingDetail.put("matched", decision.matched());
        routingDetail.put("alternatives", decision.alternatives());
        routingDetail.put("needsChoice", false);
        routingDetail.put("requestText", decision.requestText());
        putSources(routingDetail, decision.passages());

        List<ChatMessage> created = new ArrayList<>();
        created.add(appender.append(
                c, "coordinator", null, null, "routing", decision.reason(), routingDetail, goal.getId()));

        String progressContent = decision.steps().size() == 1
                ? "Working with " + decision.steps().getFirst().agent().getName() + "."
                : "Working through " + decision.steps().size() + " step(s): "
                        + decision.steps().stream()
                                .map(s -> s.agent().getName())
                                .collect(Collectors.joining(" → ")) + ".";
        Map<String, Object> progressDetail = new LinkedHashMap<>();
        progressDetail.put("goalId", goal.getId().toString());
        created.add(appender.append(
                c, "coordinator", null, null, "progress", progressContent, progressDetail, goal.getId()));
        return created;
    }

    private List<ChatMessage> applyChoice(UUID orgId, UUID conversationId, ChoiceDecision decision) {
        Conversation c = appender.lock(orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", "rules");
        detail.put("agents", List.of());
        detail.put("reason", decision.reason());
        detail.put("matched", List.of());
        detail.put("alternatives", decision.alternatives());
        detail.put("needsChoice", true);
        detail.put("requestText", decision.requestText());
        return List.of(appender.append(c, "coordinator", null, null, "routing", decision.reason(), detail, null));
    }

    private List<ChatMessage> applySimple(
            UUID orgId, UUID conversationId, String kind, String content, Map<String, Object> detail) {
        Conversation c = appender.lock(orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        return List.of(appender.append(c, "coordinator", null, null, kind, content, detail, null));
    }

    /**
     * One passage as a message carries it. {@code maxContentChars} is how much of its text: all of
     * it for the card that lists what matched, and what the agent reads - cut at a word - for the
     * sources shown under its answer, so the two can be compared line for line.
     */
    private static Map<String, Object> passageDetail(KnowledgeClient.Passage passage, int maxContentChars) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("chunkId", passage.chunkId() == null ? null : passage.chunkId().toString());
        detail.put("documentId", passage.documentId() == null ? null : passage.documentId().toString());
        detail.put("sourceId", passage.sourceId() == null ? null : passage.sourceId().toString());
        detail.put("documentTitle", passage.documentTitle());
        detail.put("uri", passage.uri());
        detail.put("pageNumber", passage.pageNumber());
        detail.put("heading", passage.heading());
        String content = passage.content() == null ? "" : passage.content();
        boolean whole = maxContentChars == Integer.MAX_VALUE;
        detail.put("content", whole ? content : truncateAtWord(content, maxContentChars));
        detail.put("score", passage.score());
        if (passage.restricted()) {
            // Marks it for whatever records the passage where others can read: the agent gets the
            // text, the conversation gets only that it was used.
            detail.put("restricted", true);
        }
        return detail;
    }

    /**
     * Records the passages an agent was given on a routing message, so its answer can show them as
     * its sources.
     *
     * <p>Only the open ones. A routing message is read by everyone in the conversation, and the
     * agent has already had the restricted passages' text in its own instruction, which is as far
     * as that text goes: nothing readable by people who cannot search the source is written here.
     */
    private static void putSources(Map<String, Object> routingDetail, List<Map<String, Object>> passages) {
        List<Map<String, Object>> open = openPassages(passages);
        if (open.isEmpty()) {
            return;
        }
        routingDetail.put("grounded", true);
        routingDetail.put("passages", open);
    }

    /** The passages a routing message recorded, as the maps they were stored as; none for a message with no sources. */
    private static List<Map<String, Object>> passagesIn(Map<String, Object> detail) {
        if (detail == null || !(detail.get("passages") instanceof List<?> stored)) {
            return List.of();
        }
        List<Map<String, Object>> passages = new ArrayList<>();
        for (Object item : stored) {
            if (passages.size() >= MAX_KNOWLEDGE_PASSAGES) {
                break;
            }
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                passages.add(copy);
            }
        }
        return passages;
    }

    /** An error message's detail: the reason, and the person's own request for "Try again" to restore. */
    private static Map<String, Object> errorDetail(String reason, String requestText) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (requestText != null && !requestText.isBlank()) {
            detail.put("requestText", requestText);
        }
        return detail;
    }

    /**
     * The readable text of a refusal, for a person to read as a plain sentence - {@code
     * ApiException.validation()} always carries the same generic message of its own, and the
     * useful text is in its {@code problem} detail instead.
     */
    private static String problemMessage(RuntimeException e) {
        if (e instanceof ApiException api
                && api.code() == ErrorCode.VALIDATION_FAILED
                && api.details().get("problem") instanceof String problem
                && !problem.isBlank()) {
            return capitalise(problem) + ".";
        }
        return "Something went wrong starting this work, so nothing was started. Try again.";
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---- Reroute (A3.10) -----------------------------------------------------------------------

    /**
     * Reroutes a routing message: cancels its goal if it is still active and the caller may cancel
     * it, then starts a new one for the chosen agent with the same original instruction.
     */
    @Transactional
    public List<ChatMessage> reroute(UUID orgId, UUID conversationId, UUID messageId, UUID chosenAgentId) {
        visible(orgId, conversationId);
        ChatMessage target = messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> ApiException.notFound("message", messageId));
        if (!"routing".equals(target.getKind())) {
            throw ApiException.validation("messageId", "Only a routing message can be rerouted.");
        }
        boolean needsChoice = Boolean.TRUE.equals(target.getDetail().get("needsChoice"));
        List<ChatMessage> seenBeforeLock = reroutesOf(conversationId, target.getPosition(), messageId);
        if (needsChoice && !seenBeforeLock.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "A choice was already made for this request.");
        }

        Agent agent = agents.findByIdAndOrgId(chosenAgentId, orgId)
                .orElseThrow(() -> ApiException.notFound("agent", chosenAgentId));
        if (!agent.isActive()) {
            throw new ApiException(ErrorCode.CONFLICT, "That agent is paused. Resume it in Agents first.");
        }

        Actor actor = RequestContext.requireActor();
        Goal activeGoal = null;
        if (target.getGoalId() != null) {
            activeGoal = goals.findByIdAndOrgId(target.getGoalId(), orgId)
                    .filter(g -> !g.isFinished())
                    .orElse(null);
        }
        if (activeGoal != null) {
            boolean mine = activeGoal.getRequestedBy() != null
                    && activeGoal.getRequestedBy().toString().equals(actor.humanId());
            if (!mine && !actor.hasPermission(Permission.Codes.TASK_CANCEL)) {
                throw new ApiException(
                                ErrorCode.PERMISSION_DENIED,
                                "Only the person who asked for this work, or "
                                        + "someone who can cancel work, can send it to someone else.")
                        .with("requiredPermission", Permission.Codes.TASK_CANCEL);
            }
            goalService.cancel(orgId, activeGoal.getId(), "Sent to " + agent.getName() + " instead.");
        }

        String requestText = target.getDetail().get("requestText") instanceof String rt && !rt.isBlank()
                ? rt
                : originalInstructionFor(conversationId, target);

        List<Agent> workspaceAgents = agents.findByOrgIdOrderByName(orgId);
        // The request being sent again sits in the thread just above its routing message; it is
        // given once below as the request, not a second time as an earlier turn.
        List<ChatMessage> chronological = withoutRequest(earlierMessages(conversationId, target), requestText);
        // The same background the first routing had: the thread so far, and the passages the agent
        // that was first given this request was given, since the new one is answering the same thing.
        List<Map<String, Object>> passages = passagesIn(target.getDetail());
        Background background = new Background(
                ThreadContext.of(chronological, nameMap(workspaceAgents), Map.of(), requesterIdFromContext()),
                passages,
                false);
        String instruction = withPreamble(background, agent.getId(), requestText);

        GoalService.NewTask task =
                new GoalService.NewTask(agent.getId(), truncateAtWord(requestText, 60), instruction, List.of());
        GoalService.NewGoal spec = new GoalService.NewGoal(
                truncateAtWord(requestText, 80),
                requestText,
                requesterIdFromContext(),
                "chat",
                conversationId,
                null,
                List.of(task));
        goalService.validate(spec);
        Goal goal = goalService.createGoal(orgId, spec, true);
        if (attachmentService != null) {
            // The files the request was sent with go with it to the agent now taking it.
            attachmentService.followReroute(orgId, conversationId, target.getGoalId(), target.getPosition(), goal.getId());
        }

        Conversation c = appender.lock(orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        List<ChatMessage> seenAfterLock = reroutesOf(conversationId, target.getPosition(), messageId);
        if (seenAfterLock.size() > seenBeforeLock.size()) {
            throw new ApiException(ErrorCode.CONFLICT, "That request was just sent to someone else.");
        }

        Map<String, Object> agentDetail = new LinkedHashMap<>();
        agentDetail.put("id", agent.getId().toString());
        agentDetail.put("name", agent.getName());
        agentDetail.put("instruction", requestText);
        String reason = "You chose " + agent.getName() + ".";
        Map<String, Object> routingDetail = new LinkedHashMap<>();
        routingDetail.put("mode", "manual");
        routingDetail.put("agents", List.of(agentDetail));
        routingDetail.put("reason", reason);
        routingDetail.put("matched", List.of());
        routingDetail.put("alternatives", List.of());
        routingDetail.put("needsChoice", false);
        routingDetail.put("rerouteOf", messageId.toString());
        routingDetail.put("requestText", requestText);
        putSources(routingDetail, passages);

        List<ChatMessage> created = new ArrayList<>();
        created.add(appender.append(c, "coordinator", null, null, "routing", reason, routingDetail, goal.getId()));
        Map<String, Object> progressDetail = new LinkedHashMap<>();
        progressDetail.put("goalId", goal.getId().toString());
        created.add(appender.append(
                c,
                "coordinator",
                null,
                null,
                "progress",
                "Working with " + agent.getName() + ".",
                progressDetail,
                goal.getId()));
        return created;
    }

    private List<ChatMessage> reroutesOf(UUID conversationId, int afterPosition, UUID messageId) {
        return messages
                .findByConversationIdAndPositionGreaterThanOrderByPosition(conversationId, afterPosition)
                .stream()
                .filter(m -> "routing".equals(m.getKind())
                        && messageId
                                .toString()
                                .equals(String.valueOf(m.getDetail().get("rerouteOf"))))
                .toList();
    }

    /**
     * The instruction the routing message answered - the nearest earlier user text message, unless
     * the routing message itself carries {@code requestText} (checked by the caller first). Falls
     * back to the routing message's own content if no such message precedes it.
     */
    private String originalInstructionFor(UUID conversationId, ChatMessage routingMessage) {
        if (routingMessage.getPosition() == 0) {
            return routingMessage.getContent();
        }
        List<ChatMessage> ordered = messages.findByConversationIdOrderByPosition(conversationId);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            ChatMessage candidate = ordered.get(i);
            if (candidate.getPosition() >= routingMessage.getPosition()) {
                continue;
            }
            if ("user".equals(candidate.getAuthorKind()) && "text".equals(candidate.getKind())) {
                return candidate.getContent();
            }
        }
        return routingMessage.getContent();
    }

    // ---- Stop and retry from chat (A3.7) ---------------------------------------------------------

    @Transactional
    public GoalActionResult stopGoal(UUID orgId, UUID conversationId, UUID goalId) {
        visible(orgId, conversationId);
        Goal goal = goalForConversation(orgId, conversationId, goalId);
        // The same rule the goal endpoint and the board follow: the person who asked for the work
        // or someone who can cancel work, and nothing that has already finished.
        goalService.requireCanStop(goal, RequestContext.requireActor());
        goalService.cancel(orgId, goalId, "Stopped from the chat.");
        return new GoalActionResult(goalId, "cancelled", null);
    }

    @Transactional
    public GoalActionResult retryGoal(UUID orgId, UUID conversationId, UUID goalId) {
        visible(orgId, conversationId);
        goalForConversation(orgId, conversationId, goalId);
        Actor actor = RequestContext.requireActor();
        GoalService.RetryResult result = goalService.retry(orgId, goalId, actor);
        return new GoalActionResult(
                goalId, result.goal().getStatus(), result.fromTask().getId());
    }

    private Goal goalForConversation(UUID orgId, UUID conversationId, UUID goalId) {
        Goal goal = goals.findByIdAndOrgId(goalId, orgId).orElseThrow(() -> ApiException.notFound("goal", goalId));
        if (!conversationId.equals(goal.getConversationId())) {
            throw ApiException.notFound("goal", goalId);
        }
        return goal;
    }

    // ---- Answer from documents (A3.9) -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Transactional
    public List<ChatMessage> answerFromDocuments(
            UUID orgId, UUID conversationId, UUID messageId, UUID explicitAgentId) {
        visible(orgId, conversationId);
        ChatMessage target = messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> ApiException.notFound("message", messageId));
        Object passagesRaw = target.getDetail().get("passages");
        if (!"documents".equals(target.getKind())
                || !Boolean.TRUE.equals(target.getDetail().get("grounded"))
                || !(passagesRaw instanceof List<?> passageList)
                || passageList.isEmpty()) {
            throw ApiException.validation("messageId", "only a documents message with passages can be answered from");
        }

        List<Agent> workspaceAgents = agents.findByOrgIdOrderByName(orgId);
        Agent agent = explicitAgentId == null
                ? null
                : agents.findByIdAndOrgId(explicitAgentId, orgId)
                        .filter(Agent::isActive)
                        .orElse(null);
        if (agent == null) {
            agent = generalEmployee.activeIn(workspaceAgents).orElse(null);
        }
        if (agent == null) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "General Employee is paused. Mention an agent to answer from these passages.");
        }

        String query = String.valueOf(target.getDetail().get("query"));
        List<Map<String, Object>> passages = (List<Map<String, Object>>) passageList;
        String instruction = DocumentsPrompt.build(query, passages);

        Conversation c = appender.lock(orgId, conversationId)
                .orElseThrow(() -> ApiException.notFound("conversation", conversationId));
        boolean already = messages
                .findByConversationIdAndPositionGreaterThanOrderByPosition(conversationId, target.getPosition())
                .stream()
                .anyMatch(m -> "routing".equals(m.getKind())
                        && messageId
                                .toString()
                                .equals(String.valueOf(m.getDetail().get("fromDocumentsMessageId"))));
        if (already) {
            throw new ApiException(ErrorCode.CONFLICT, "An answer from these passages was already started.");
        }

        GoalService.NewTask task =
                new GoalService.NewTask(agent.getId(), truncateAtWord(query, 60), instruction, List.of());
        GoalService.NewGoal spec = new GoalService.NewGoal(
                truncateAtWord(query, 80),
                query,
                requesterIdFromContext(),
                "chat",
                conversationId,
                null,
                List.of(task));
        goalService.validate(spec);
        Goal goal = goalService.createGoal(orgId, spec, true);

        Agent finalAgent = agent;
        String reason = "You asked " + finalAgent.getName() + " to answer from these passages.";
        Map<String, Object> agentDetail = new LinkedHashMap<>();
        agentDetail.put("id", finalAgent.getId().toString());
        agentDetail.put("name", finalAgent.getName());
        agentDetail.put("instruction", instruction);
        Map<String, Object> routingDetail = new LinkedHashMap<>();
        routingDetail.put("mode", "manual");
        routingDetail.put("agents", List.of(agentDetail));
        routingDetail.put("reason", reason);
        routingDetail.put("matched", List.of());
        routingDetail.put("alternatives", List.of());
        routingDetail.put("needsChoice", false);
        routingDetail.put("fromDocumentsMessageId", messageId.toString());
        routingDetail.put("requestText", query);

        List<ChatMessage> created = new ArrayList<>();
        created.add(appender.append(c, "coordinator", null, null, "routing", reason, routingDetail, goal.getId()));
        Map<String, Object> progressDetail = new LinkedHashMap<>();
        progressDetail.put("goalId", goal.getId().toString());
        created.add(appender.append(
                c,
                "coordinator",
                null,
                null,
                "progress",
                "Working with " + finalAgent.getName() + ".",
                progressDetail,
                goal.getId()));
        return created;
    }

    // ---- Small helpers -------------------------------------------------------------------------

    /** The turns - people's messages and agents' answers - before a message, oldest first. */
    private List<ChatMessage> earlierMessages(UUID conversationId, ChatMessage after) {
        List<ChatMessage> newestFirst = messages.findEarlierTurns(
                conversationId, after.getPosition(), PageRequest.of(0, EARLIER_TURNS_WINDOW));
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    /** The turns without the newest person's message that says exactly {@code request}, when there is one. */
    private static List<ChatMessage> withoutRequest(List<ChatMessage> chronological, String request) {
        List<ChatMessage> kept = new ArrayList<>(chronological);
        for (int i = kept.size() - 1; i >= 0; i--) {
            ChatMessage turn = kept.get(i);
            if ("user".equals(turn.getAuthorKind())
                    && "text".equals(turn.getKind())
                    && turn.getContent() != null
                    && turn.getContent().strip().equals(request.strip())) {
                kept.remove(i);
                break;
            }
        }
        return kept;
    }

    private static Map<UUID, String> nameMap(List<Agent> workspaceAgents) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Agent agent : workspaceAgents) {
            names.put(agent.getId(), agent.getName());
        }
        return names;
    }

    private static Agent lastAnswerAgent(List<ChatMessage> chronological, List<Agent> workspaceAgents) {
        for (int i = chronological.size() - 1; i >= 0; i--) {
            ChatMessage m = chronological.get(i);
            if ("answer".equals(m.getKind()) && m.getAgentId() != null) {
                Agent agent = agentById(workspaceAgents, m.getAgentId());
                if (agent != null) {
                    return agent;
                }
            }
        }
        return null;
    }

    /** Requests that ask to be told something, which the workspace's documents may answer. */
    private static final Pattern INFORMATIONAL = Pattern.compile(
            "^\\s*(tell|explain|describe|summari[sz]e|give me|show me|walk me through|remind me|what|who|where|"
                    + "when|why|how|which|do we|does|is|are|can you tell)\\b"
                    + "|\\b(knowledge base|our documents|the documents|the docs|uploaded|on file)\\b",
            Pattern.CASE_INSENSITIVE);

    static boolean looksInformational(String text) {
        return text != null && INFORMATIONAL.matcher(text).find();
    }

    /**
     * What to search the documents for. A follow-up such as "it is in the knowledge base" names no
     * subject of its own, so the person's previous request is searched with it.
     */
    static String searchQueryFor(String text, List<ChatMessage> chronological) {
        if (text == null || chronological == null || !(looksLikeFollowUp(text) || mentionsDocuments(text))) {
            return text;
        }
        for (int i = chronological.size() - 1; i >= 0; i--) {
            ChatMessage message = chronological.get(i);
            if ("user".equals(message.getAuthorKind())
                    && "text".equals(message.getKind())
                    && message.getContent() != null
                    && !message.getContent().equals(text)) {
                return message.getContent() + " " + text;
            }
        }
        return text;
    }

    private static final Pattern DOCUMENTS_WORDS =
            Pattern.compile("\\b(knowledge base|documents?|docs|uploaded|on file)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean mentionsDocuments(String text) {
        return DOCUMENTS_WORDS.matcher(text).find();
    }

    /**
     * Puts the passages that answer a request ahead of it, in the same shape as the thread
     * preamble: context first, and a closing "Request:" line before the request itself.
     *
     * <p>The passages are the list stored as the answer's sources, so what the person is shown is
     * what the agent read. Each is a numbered title line, then its text on one line, which is how a
     * later step of a chain finds out which passages the first one was given.
     */
    static String withKnowledge(String preamble, List<Map<String, Object>> passages) {
        StringBuilder block = new StringBuilder(GoalService.PASSAGES_HEADING + " " + DocumentsPrompt.UNTRUSTED_RULE
                + " " + DocumentsPrompt.CITATION_RULE + "\n");
        int index = 1;
        for (Map<String, Object> passage : passages) {
            if (index > MAX_KNOWLEDGE_PASSAGES) {
                break;
            }
            String title = DocumentsPrompt.withoutLookAlikes(
                    truncateAtWord(String.valueOf(passage.get("documentTitle")), 200));
            block.append('[').append(index).append("] ").append(title);
            Object page = passage.get("pageNumber");
            if (page != null) {
                block.append(", page ").append(page);
            }
            String content = String.valueOf(passage.getOrDefault("content", ""));
            // The title line stays as it was - the goal's later steps read the passage titles from
            // it - and the text, still on the one line after it, sits between the passage's tags.
            block.append('\n')
                    .append(DocumentsPrompt.wrap(index, title, truncateAtWord(content, MAX_KNOWLEDGE_PASSAGE_CHARS)))
                    .append("\n\n");
            index++;
        }
        String existing = preamble == null ? "" : preamble;
        return existing.isEmpty() ? block.append("Request:\n").toString() : block + existing;
    }

    static final int MAX_KNOWLEDGE_PASSAGES = 6;
    static final int MAX_KNOWLEDGE_PASSAGE_CHARS = 900;

    /** Words that point back at earlier work rather than naming new work. */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "\\b(it|that|this|these|those|them|again|instead|also|shorter|longer|simpler|more|less|"
                    + "rewrite|reword|rephrase|redo|change|edit|fix|update|expand|shorten|translate|same|"
                    + "tweak|revise|polish|improve|add|remove|now|then|another|version)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A short message that refers back to earlier work, such as "make it shorter" or "now for managers". */
    static boolean looksLikeFollowUp(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.strip();
        return trimmed.split("\\s+").length <= 12 && FOLLOW_UP.matcher(trimmed).find();
    }

    /**
     * Puts everything an agent needs to know ahead of a request: the passages that bear on it, then
     * the conversation so far, then a closing "Request:" line. The request itself is never cut.
     *
     * <p>When the whole comes to more than {@value #MAX_INSTRUCTION_CHARS} characters it is the
     * older turns that give way, oldest first. The header, the agent's last reply, the closing
     * line and every passage stay whole: a reply that is cut short here would be "sent" or
     * "shortened" as though it were all there was.
     *
     * @param agentId the agent that reads it, whose own last reply is "Your last reply"
     */
    static String withPreamble(Background background, UUID agentId, String instruction) {
        String body = instruction == null ? "" : instruction;
        if (background == null || background.isEmpty()) {
            return body;
        }
        String front = "";
        for (int dropped = 0; dropped <= background.history().droppable(); dropped++) {
            front = front(background, agentId, dropped);
            if (front.length() + body.length() <= MAX_INSTRUCTION_CHARS) {
                break;
            }
        }
        return front + body;
    }

    /** What goes ahead of the request, with the {@code dropped} oldest turns of the conversation left out. */
    private static String front(Background background, UUID agentId, int dropped) {
        String thread = background.history().render(agentId, dropped);
        if (!background.passages().isEmpty()) {
            return withKnowledge(thread, background.passages());
        }
        if (background.uncovered()) {
            return NO_COVERAGE_NOTE + "\n\n" + (thread.isEmpty() ? "Request:\n" : thread);
        }
        return thread;
    }

    private String colleagueBlock(List<Agent> workspaceAgents, Agent fallbackAgent) {
        List<Agent> specialists = workspaceAgents.stream()
                .filter(Agent::isActive)
                .filter(a -> !a.getId().equals(fallbackAgent.getId()))
                .limit(MAX_COLLEAGUES)
                .toList();
        if (specialists.isEmpty()) {
            return "";
        }
        String list = specialists.stream()
                .map(a -> a.getName() + " (" + truncateAtWord(summaryOf(a), MAX_COLLEAGUE_SUMMARY_CHARS) + ")")
                .collect(Collectors.joining("; "));
        return "\n\nAbout this workspace: besides you, it has " + specialists.size() + " AI "
                + (specialists.size() == 1 ? "colleague" : "colleagues") + ": " + list
                + ". Point the person to the right one when a request is really theirs.";
    }

    private String summaryOf(Agent agent) {
        if (agent.getCurrentVersionId() == null) {
            return "";
        }
        return versions.findById(agent.getCurrentVersionId())
                .map(v -> firstSentence(v.getSystemPrompt()))
                .orElse("");
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
        return sentence.find() ? sentence.group(1) : text;
    }

    private static List<Map<String, Object>> pausedMentionAlternatives(Agent fallback, List<Agent> workspaceAgents) {
        List<Agent> ordered = new ArrayList<>();
        if (fallback != null) {
            ordered.add(fallback);
        }
        for (Agent agent : workspaceAgents) {
            if (agent.isActive() && (fallback == null || !agent.getId().equals(fallback.getId()))) {
                ordered.add(agent);
            }
        }
        return ordered.stream().limit(4).map(a -> scoredAgentMap(a, 0)).toList();
    }

    private static Map<String, Object> scoredAgentMap(Agent agent, int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", agent.getId().toString());
        m.put("name", agent.getName());
        m.put("score", score);
        return m;
    }

    private static List<Map<String, Object>> scoredList(List<RuleRouter.ScoredAgent> scored) {
        return scored.stream().map(s -> scoredAgentMap(s.agent(), s.score())).toList();
    }

    private static String choiceReasonFor(RuleRouter.Reason reason) {
        return switch (reason) {
            case TIE -> TIE_MESSAGE;
            case NO_AGENTS -> NO_AGENTS_MESSAGE;
            case NO_MATCH, NONE -> NO_MATCH_MESSAGE;
        };
    }

    private Map<UUID, List<String>> toolServersByAgent(List<Agent> workspaceAgents) {
        Map<UUID, List<String>> byAgent = new LinkedHashMap<>();
        for (Agent agent : workspaceAgents) {
            byAgent.put(
                    agent.getId(),
                    grants.findByAgentIdAndEnabledTrue(agent.getId()).stream()
                            .map(AgentToolGrant::getServer)
                            .distinct()
                            .toList());
        }
        return byAgent;
    }

    private static Agent agentById(List<Agent> agents, UUID agentId) {
        return agents.stream()
                .filter(agent -> agent.getId().equals(agentId))
                .findFirst()
                .orElse(null);
    }

    private static String mentionReason(List<Agent> chosen) {
        if (chosen.size() == 1) {
            return "You mentioned @" + chosen.getFirst().getName() + ".";
        }
        return "You mentioned " + chosen.stream().map(a -> "@" + a.getName()).collect(Collectors.joining(" and "))
                + ".";
    }

    private static String ruleReason(List<RuleRouter.Routed> steps) {
        return steps.stream()
                .map(step -> {
                    String words =
                            step.matched().stream().map(w -> "\"" + w + "\"").collect(Collectors.joining(", "));
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
        return RequestContext.actor()
                .map(actor -> parseUuidOrNull(actor.humanId()))
                .orElse(null);
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
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
