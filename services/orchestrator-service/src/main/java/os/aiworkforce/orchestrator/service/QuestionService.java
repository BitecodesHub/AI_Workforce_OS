package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Questions a run stops to ask the person it works for.
 *
 * <p>Three guarantees are held here, each because breaking it would steer work wrongly:
 *
 * <ul>
 *   <li><b>Only the right people answer.</b> The goal's requester, or anyone who can cancel
 *       work - an answer steers someone else's work, and people who can already stop it may also
 *       steer it. Checked on every answer and extension, never only in the interface.
 *   <li><b>An answer never reaches stopped work.</b> A question whose run is no longer waiting
 *       for it is closed when anyone tries to answer it, and every stop withdraws its run's
 *       question with a conditional bulk update, so a stop and an answer at the same moment
 *       simply race and one wins cleanly.
 *   <li><b>A question never waits forever.</b> It closes after a window (24 hours by default,
 *       extendable up to 7 days), and the run then finishes with what it has.
 * </ul>
 *
 * <p>This class decides and records; it never resumes a run. The caller that commits an answer
 * submits the resume ({@code QuestionController}), and the sweeps resume anything left behind.
 * It depends on no engine service, so nothing here can form a cycle.
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    static final int MAX_ASKS_PER_RUN = 3;
    static final Duration MAX_OPEN = Duration.ofDays(7);
    static final int MAX_OTHER = 2_000;
    static final int MAX_NOTE = 2_000;
    public static final String STOPPED_WORK = "The work this question belonged to has stopped.";
    static final String NO_ANSWER = "No answer arrived before the question closed.";
    static final String SKIPPED = "Skipped: the person asked the agent to use its judgement.";
    static final Set<String> VIA = Set.of("chat", "orchestrator", "run", "approvals");

    static final String SCHEDULED_REASON = "No person can answer questions during scheduled work. Choose a "
            + "sensible default, do the work, and say what you assumed.";
    static final String LIMIT_REASON =
            "This run has already asked as many questions as it may. Use what you " + "have, and say what you assumed.";
    static final String UNANSWERED_REASON = "An earlier question went unanswered, so do not ask again. Finish "
            + "with what you can, and say what you still need.";

    private static final TypeReference<List<AskPersonTool.Question>> QUESTIONS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RunQuestions questions;
    private final Runs runs;
    private final Tasks tasks;
    private final Goals goals;
    private final ObjectMapper json;
    private final AuditClient audit;
    private final Duration window;

    public QuestionService(
            RunQuestions questions,
            Runs runs,
            Tasks tasks,
            Goals goals,
            ObjectMapper json,
            AuditClient audit,
            @Value("${aiwos.questions.window:PT24H}") Duration window) {
        this.questions = questions;
        this.runs = runs;
        this.tasks = tasks;
        this.goals = goals;
        this.json = json;
        this.audit = audit;
        this.window = window;
    }

    /**
     * Whether a run may ask, and whether it ever has.
     *
     * @param allowed the ask tool may be used now
     * @param everAsked the run has asked before, so its conversation refers to the ask tool and
     *     the tool must stay defined for the provider even when asking is no longer allowed
     * @param reason why asking is not allowed, written for the model; null when it is
     */
    public record AskPolicy(boolean allowed, boolean everAsked, String reason) {}

    /** One reply to one question: the option labels chosen, and an answer in the person's own words. */
    public record AnswerItem(String questionId, List<String> selected, String other) {}

    /**
     * @param via where it was answered: {@code chat}, {@code orchestrator}, {@code run} or {@code approvals}
     */
    public record AnswerInput(List<AnswerItem> answers, String note, boolean skipped, String via) {}

    /** @param newlyAnswered false when this was the same person repeating the same answer */
    public record AnswerOutcome(RunQuestion question, boolean newlyAnswered) {}

    /**
     * A question as a person sees it.
     *
     * @param canAnswer whether the viewer may answer it
     * @param extendable whether one more extension still fits inside the seven-day cap
     * @param runStatus the run's current status, {@code unknown} when the run is gone
     */
    public record QuestionView(
            UUID id,
            UUID runId,
            UUID taskId,
            UUID goalId,
            UUID conversationId,
            UUID agentId,
            String goalTitle,
            String status,
            List<Map<String, Object>> questions,
            Map<String, Object> answer,
            UUID answeredBy,
            String answeredVia,
            Instant answeredAt,
            UUID requestedBy,
            Instant createdAt,
            Instant expiresAt,
            String closedReason,
            boolean canAnswer,
            boolean extendable,
            String runStatus) {}

    // ---- Asking ---------------------------------------------------------------------------------

    /**
     * Whether this run may ask a question now.
     *
     * <p>A scheduled goal never asks, because nobody is watching and a waiting goal would block the
     * schedule. A run asks at most three times, and never again after a question went unanswered:
     * guessing past silence is safer than asking into it.
     */
    @Transactional(readOnly = true)
    public AskPolicy policyFor(Run run) {
        long asked = questions.countByRunId(run.getId());
        boolean everAsked = asked > 0;
        if (run.getTaskId() != null) {
            Goal goal = tasks.findById(run.getTaskId())
                    .flatMap(task -> goals.findById(task.getGoalId()))
                    .orElse(null);
            if (goal != null && "schedule".equals(goal.getSource())) {
                return new AskPolicy(false, everAsked, SCHEDULED_REASON);
            }
        }
        if (asked >= MAX_ASKS_PER_RUN) {
            return new AskPolicy(false, everAsked, LIMIT_REASON);
        }
        if (questions.existsByRunIdAndStatus(run.getId(), "expired")) {
            return new AskPolicy(false, everAsked, UNANSWERED_REASON);
        }
        return new AskPolicy(true, everAsked, null);
    }

    /**
     * Records the question a run is about to park on.
     *
     * <p>Idempotent on the call id: a replay of the same ask returns the row it already created.
     * Joins the caller's transaction, which also parks the run.
     */
    @Transactional
    public RunQuestion raise(Run run, Agent agent, String toolCallId, AskPersonTool.Ask ask) {
        Optional<RunQuestion> existing = questions.findByRunIdAndToolCallId(run.getId(), toolCallId);
        if (existing.isPresent()) {
            return existing.get();
        }
        RunQuestion question = new RunQuestion();
        question.setId(UuidV7.generate());
        question.setOrgId(run.getOrgId());
        question.setRunId(run.getId());
        question.setTaskId(run.getTaskId());
        question.setAgentId(agent.getId());
        question.setToolCallId(toolCallId);
        if (run.getTaskId() != null) {
            Goal goal = tasks.findById(run.getTaskId())
                    .flatMap(task -> goals.findById(task.getGoalId()))
                    .orElse(null);
            if (goal != null) {
                question.setGoalId(goal.getId());
                question.setConversationId(goal.getConversationId());
                question.setRequestedBy(goal.getRequestedBy());
            }
        } else {
            // A run started directly on an agent belongs to whoever started it. The loop's own
            // copy of the run is detached and never learned its creator, so it is read afresh.
            String createdBy = runs.findById(run.getId()).map(Run::getCreatedBy).orElse(run.getCreatedBy());
            question.setRequestedBy(parseUuidOrNull(createdBy));
        }
        question.setExpiresAt(Instant.now().plus(window));
        question.setQuestionsJson(write(ask.questions()));
        return questions.save(question);
    }

    /** The detail of the trace's {@code question} step. */
    public Map<String, Object> askStepDetail(RunQuestion question) {
        List<AskPersonTool.Question> asked = readQuestions(question);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("questionId", question.getId().toString());
        detail.put("toolCallId", question.getToolCallId());
        detail.put("tool", AskPersonTool.NAME);
        detail.put("summary", askSummary(asked));
        detail.put("questions", json.convertValue(asked, MAPS));
        return detail;
    }

    private static String askSummary(List<AskPersonTool.Question> asked) {
        if (asked.size() == 1) {
            return "Asked: " + asked.getFirst().question();
        }
        return "Asked " + asked.size() + " questions: "
                + asked.stream().map(AskPersonTool.Question::header).collect(Collectors.joining(", "));
    }

    // ---- Answering ------------------------------------------------------------------------------

    /**
     * Records a person's answer.
     *
     * <p>A refusal does not roll back what was written before it. The only writes before a
     * refusal close a question that can no longer be answered - its time is up, or its work has
     * stopped - and that must stick whether or not this answer could be accepted.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AnswerOutcome answer(UUID orgId, UUID questionId, AnswerInput input) {
        Actor actor = RequestContext.requireActor();
        RunQuestion question = questions
                .lockByIdAndOrgId(questionId, orgId)
                .orElseThrow(() -> ApiException.notFound("question", questionId));
        requireCanAnswer(question, actor, "answer this question");
        closeIfWorkStopped(question);

        Map<String, Object> normalised = normalise(input);

        if (!question.isPending()) {
            if ("answered".equals(question.getStatus())) {
                if (Objects.equals(
                                question.getAnsweredBy() == null
                                        ? null
                                        : question.getAnsweredBy().toString(),
                                actor.humanId())
                        && sameAnswer(question.getAnswerJson(), normalised)) {
                    // The same person sending the same answer twice - a double click, a retried
                    // request - gets the answer they already gave, not an error.
                    return new AnswerOutcome(question, false);
                }
                throw new ApiException(ErrorCode.QUESTION_ALREADY_ANSWERED).with("status", "answered");
            }
            throw new ApiException(ErrorCode.QUESTION_CLOSED).with("status", question.getStatus());
        }

        Instant now = Instant.now();
        if (question.hasExpired(now)) {
            // The sweep would close it within minutes anyway; closing it here tells this person
            // the truth now, and the resume sweep carries the run on from the expiry.
            close(question, "expired", NO_ANSWER);
            throw new ApiException(ErrorCode.QUESTION_CLOSED).with("status", "expired");
        }

        validate(question, input, normalised);

        question.setStatus("answered");
        question.setAnswerJson(write(normalised));
        question.setAnsweredBy(parseUuidOrNull(actor.humanId()));
        question.setAnsweredVia(input.via());
        question.setAnsweredAt(now);
        questions.save(question);
        log.info("Question {} for run {} answered via {}", question.getId(), question.getRunId(), input.via());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", question.getRunId().toString());
        detail.put("skipped", input.skipped());
        detail.put("via", input.via());
        LifecycleAnnouncer.afterCommit(() ->
                audit.record(orgId, actor, "question.answer", "question", questionId.toString(), "succeeded", detail));
        return new AnswerOutcome(question, true);
    }

    /**
     * Gives a question one more window, up to seven days from when it was asked.
     *
     * <p>As with an answer, a question whose work has stopped is closed rather than extended.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RunQuestion extend(UUID orgId, UUID questionId) {
        Actor actor = RequestContext.requireActor();
        RunQuestion question = questions
                .lockByIdAndOrgId(questionId, orgId)
                .orElseThrow(() -> ApiException.notFound("question", questionId));
        requireCanAnswer(question, actor, "extend this question");
        if (!question.isPending()) {
            throw new ApiException(ErrorCode.QUESTION_CLOSED).with("status", question.getStatus());
        }
        closeIfWorkStopped(question);

        Instant now = Instant.now();
        Instant from = question.getExpiresAt().isAfter(now) ? question.getExpiresAt() : now;
        Instant newExpiry = from.plus(window);
        if (newExpiry.isAfter(openedAt(question).plus(MAX_OPEN))) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "This question has been open as long as it can be. Answer it, or let the agent decide.");
        }
        question.setExpiresAt(newExpiry);
        questions.save(question);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", question.getRunId().toString());
        detail.put("expiresAt", newExpiry.toString());
        LifecycleAnnouncer.afterCommit(() ->
                audit.record(orgId, actor, "question.extend", "question", questionId.toString(), "succeeded", detail));
        return question;
    }

    /**
     * The goal's requester, or anyone who can cancel work, and in either case someone who can
     * start work: an answer steers it.
     */
    public boolean canAnswer(RunQuestion question, Actor actor) {
        if (actor == null) {
            return false;
        }
        boolean requester = question.getRequestedBy() != null
                && question.getRequestedBy().toString().equals(actor.humanId());
        boolean steers = actor.hasPermission(Permission.Codes.TASK_CANCEL) || requester;
        boolean mayStartWork =
                actor.hasPermission(Permission.Codes.TASK_CREATE) || actor.hasPermission(Permission.Codes.AGENT_RUN);
        return steers && mayStartWork;
    }

    private void requireCanAnswer(RunQuestion question, Actor actor, String action) {
        if (!canAnswer(question, actor)) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "Only the person who asked for this work, or someone who can cancel work, can " + action
                                    + ".")
                    .with("requiredPermission", Permission.Codes.TASK_CANCEL);
        }
    }

    /**
     * Closes a pending question whose run is no longer waiting for it, and refuses the caller.
     *
     * <p>This is the stop path that could not withdraw it - a run finished some other way - and a
     * person must never be told their answer is being used by work that has stopped.
     */
    private void closeIfWorkStopped(RunQuestion question) {
        if (!question.isPending()) {
            return;
        }
        boolean waiting = runs.findById(question.getRunId())
                .map(run -> "waiting_input".equals(run.getStatus()))
                .orElse(false);
        if (!waiting) {
            close(question, "cancelled", STOPPED_WORK);
            throw new ApiException(ErrorCode.QUESTION_CLOSED).with("status", "cancelled");
        }
    }

    private void close(RunQuestion question, String status, String reason) {
        question.setStatus(status);
        question.setClosedReason(reason);
        questions.save(question);
    }

    /** Trimmed, with blank answers in the person's own words dropped and repeated labels removed. */
    private static Map<String, Object> normalise(AnswerInput input) {
        List<Map<String, Object>> answers = new ArrayList<>();
        if (input.answers() != null) {
            for (AnswerItem item : input.answers()) {
                if (item == null) {
                    continue;
                }
                LinkedHashSet<String> selected = new LinkedHashSet<>();
                if (item.selected() != null) {
                    for (String label : item.selected()) {
                        if (label != null && !label.isBlank()) {
                            selected.add(label.strip());
                        }
                    }
                }
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put(
                        "questionId",
                        item.questionId() == null ? "" : item.questionId().strip());
                answer.put("selected", new ArrayList<>(selected));
                answer.put("other", blankToNull(item.other()));
                answers.add(answer);
            }
        }
        Map<String, Object> normalised = new LinkedHashMap<>();
        normalised.put("answers", answers);
        normalised.put("note", blankToNull(input.note()));
        normalised.put("skipped", input.skipped());
        return normalised;
    }

    /**
     * Compares parsed trees, never text: JSONB reorders object keys, so two equal answers can come
     * back from the database spelled differently.
     */
    private boolean sameAnswer(String storedJson, Map<String, Object> normalised) {
        if (storedJson == null) {
            return false;
        }
        try {
            return json.readTree(storedJson).equals(json.valueToTree(normalised));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void validate(RunQuestion question, AnswerInput input, Map<String, Object> normalised) {
        if (input.via() == null || !VIA.contains(input.via())) {
            throw ApiException.validation("via", "must be one of chat, orchestrator, run or approvals");
        }
        String note = (String) normalised.get("note");
        if (note != null && note.length() > MAX_NOTE) {
            throw ApiException.validation("note", "keep a reply to 2,000 characters or fewer");
        }
        List<Map<String, Object>> answers = (List<Map<String, Object>>) normalised.get("answers");
        if (input.skipped()) {
            if (!answers.isEmpty()) {
                throw ApiException.validation("answers", "leave answers empty when letting the agent decide");
            }
            return;
        }

        Map<String, AskPersonTool.Question> asked = new LinkedHashMap<>();
        for (AskPersonTool.Question q : readQuestions(question)) {
            asked.put(q.id(), q);
        }
        Set<String> answered = new HashSet<>();
        for (Map<String, Object> answer : answers) {
            String id = (String) answer.get("questionId");
            AskPersonTool.Question q = asked.get(id);
            if (q == null || !answered.add(id)) {
                throw ApiException.validation("answers", "names a question that was not asked");
            }
            List<String> selected = (List<String>) answer.get("selected");
            String other = (String) answer.get("other");
            if (other != null && other.length() > MAX_OTHER) {
                throw ApiException.validation("answers", "keep a written answer to 2,000 characters or fewer");
            }
            Set<String> offered =
                    q.options().stream().map(AskPersonTool.Option::label).collect(Collectors.toSet());
            if (!offered.containsAll(selected)) {
                throw ApiException.validation("answers", "picks an option that was not offered");
            }
            int given = selected.size() + (other != null ? 1 : 0);
            if (!q.multiSelect() && given != 1) {
                throw ApiException.validation("answers", "choose one option, or write your own answer");
            }
            if (q.multiSelect() && given < 1) {
                throw ApiException.validation("answers", "choose at least one option, or write your own answer");
            }
        }
        // A reply in the person's own words may answer the questions only partly; without one,
        // every question needs its own answer.
        if (note == null && !answered.containsAll(asked.keySet())) {
            throw ApiException.validation("answers", "answer every question, or write a reply in your own words");
        }
    }

    // ---- Expiry, withdrawal and resuming --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UUID> overdueIds(int limit) {
        return questions.findExpiredIds(Instant.now(), PageRequest.of(0, limit));
    }

    /**
     * Expires one overdue question, in a transaction of its own under a row lock.
     *
     * <p>One per transaction, so an answer committing to one question at the same moment can never
     * roll back the expiry of the rest of the batch.
     *
     * @return the run to resume, or empty when a concurrent answer or withdrawal got there first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RunRef> expireOne(UUID questionId) {
        RunQuestion question = questions.lockById(questionId).orElse(null);
        if (question == null || !question.hasExpired(Instant.now())) {
            return Optional.empty();
        }
        close(question, "expired", NO_ANSWER);
        log.info("Question {} for run {} expired unanswered", question.getId(), question.getRunId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", question.getRunId().toString());
        UUID orgId = question.getOrgId();
        LifecycleAnnouncer.afterCommit(() -> audit.record(
                orgId, Actor.SYSTEM, "question.expire", "question", questionId.toString(), "succeeded", detail));
        return Optional.of(new RunRef(question.getOrgId(), question.getRunId()));
    }

    @Transactional(readOnly = true)
    public List<UUID> strandedPendingIds(int limit) {
        return questions.findStrandedPendingIds(PageRequest.of(0, limit));
    }

    /** Closes a pending question whose run stopped by a path that could not withdraw it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeStranded(UUID questionId) {
        RunQuestion question = questions.lockById(questionId).orElse(null);
        if (question == null || !question.isPending()) {
            return;
        }
        boolean waiting = runs.findById(question.getRunId())
                .map(run -> "waiting_input".equals(run.getStatus()))
                .orElse(false);
        if (!waiting) {
            close(question, "cancelled", STOPPED_WORK);
            log.info("Question {} closed: its run {} is no longer waiting", question.getId(), question.getRunId());
        }
    }

    /**
     * Withdraws a stopped run's pending question with a conditional bulk update, never by loading
     * and saving it. Joins the caller's transaction, which holds the run's lock.
     *
     * @return how many were withdrawn
     */
    @Transactional
    public int cancelForRun(UUID runId, String reason) {
        return questions.withdrawPending(runId, reason, Instant.now());
    }

    /** Runs parked on a question that has since been answered or expired, for the resume sweep. */
    @Transactional(readOnly = true)
    public List<RunRef> awaitingResume(Instant cutoff, int limit) {
        return questions.findAwaitingResume(cutoff, PageRequest.of(0, limit)).stream()
                .map(question -> new RunRef(question.getOrgId(), question.getRunId()))
                .distinct()
                .toList();
    }

    /**
     * The run's newest question, whatever its status. Deliberately not annotated: the runner reads
     * it inside its resume claim, and it must see the state that claim was made on.
     */
    public Optional<RunQuestion> latestForRun(UUID runId) {
        return questions.findFirstByRunIdOrderByCreatedAtDesc(runId);
    }

    // ---- For chat -------------------------------------------------------------------------------

    /** [conversationId, requestedBy] for every pending question in these conversations. */
    @Transactional(readOnly = true)
    public List<Object[]> pendingByConversation(UUID orgId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return questions.pendingByConversation(orgId, ids);
    }

    /** Conversations holding a pending question this person asked for. */
    @Transactional(readOnly = true)
    public List<UUID> conversationsNeedingAnswerFrom(UUID orgId, UUID me) {
        if (me == null) {
            return List.of();
        }
        return questions.conversationsNeedingAnswerFrom(orgId, me);
    }

    /** Unlinks a deleted conversation's questions, which stay on their runs. */
    @Transactional
    public int detachConversation(UUID orgId, UUID conversationId) {
        return questions.detachConversation(orgId, conversationId);
    }

    // ---- What the model and the trace are told --------------------------------------------------

    /** The ask call's result, as the model receives it. */
    public String modelResult(RunQuestion question) {
        ObjectNode result = json.createObjectNode();
        if ("answered".equals(question.getStatus())) {
            Map<String, Object> answer = readAnswer(question);
            if (Boolean.TRUE.equals(answer.get("skipped"))) {
                result.put("status", "skipped");
                result.put(
                        "instruction",
                        "The person asked you to use your own judgement. Choose the recommended "
                                + "option, or the most sensible default, say which you chose, and continue.");
                return result.toString();
            }
            Map<String, AskPersonTool.Question> asked = new HashMap<>();
            readQuestions(question).forEach(q -> asked.put(q.id(), q));
            result.put("status", "answered");
            ArrayNode answers = result.putArray("answers");
            for (Map<String, Object> item : answerItems(answer)) {
                AskPersonTool.Question q = asked.get(String.valueOf(item.get("questionId")));
                ObjectNode entry = answers.addObject();
                entry.put("header", q == null ? "" : q.header());
                entry.put("question", q == null ? "" : q.question());
                ArrayNode selected = entry.putArray("selected");
                selectedOf(item).forEach(selected::add);
                putNullable(entry, "other", item.get("other"));
            }
            putNullable(result, "note", answer.get("note"));
            result.put(
                    "instruction",
                    "These are the person's answers. Continue the work using them, and do not "
                            + "ask the same thing again.");
            return result.toString();
        }
        result.put("status", "no_answer");
        result.put(
                "instruction",
                "Nobody answered in time. Do not act on a guess where it matters. Finish what you "
                        + "can, and say exactly what you still need.");
        return result.toString();
    }

    /** One line for the trace's answering step. */
    public String resultSummary(RunQuestion question) {
        if (!"answered".equals(question.getStatus())) {
            return NO_ANSWER;
        }
        Map<String, Object> answer = readAnswer(question);
        if (Boolean.TRUE.equals(answer.get("skipped"))) {
            return SKIPPED;
        }
        Map<String, String> headers = new HashMap<>();
        readQuestions(question).forEach(q -> headers.put(q.id(), q.header()));
        List<Map<String, Object>> items = answerItems(answer);
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<String> said = new ArrayList<>(selectedOf(item));
            if (item.get("other") instanceof String other && !other.isBlank()) {
                said.add(other);
            }
            String text = String.join(", ", said);
            parts.add(
                    items.size() > 1
                            ? headers.getOrDefault(String.valueOf(item.get("questionId")), "") + ": " + text
                            : text);
        }
        if (answer.get("note") instanceof String note && !note.isBlank()) {
            parts.add(note);
        }
        String summary = "Answered: " + String.join("; ", parts);
        return summary.length() <= 500 ? summary : summary.substring(0, 499) + "…";
    }

    // ---- Views ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<RunQuestion> find(UUID orgId, UUID questionId) {
        return questions.findByIdAndOrgId(questionId, orgId);
    }

    /** Pending questions, the soonest to close first. */
    @Transactional(readOnly = true)
    public List<RunQuestion> pending(UUID orgId, int limit) {
        return questions.findPending(orgId, PageRequest.of(0, limit));
    }

    /** Every question, newest first. */
    @Transactional(readOnly = true)
    public List<RunQuestion> recent(UUID orgId, int limit) {
        return questions.findRecent(orgId, PageRequest.of(0, limit));
    }

    /** One run's questions, oldest first. The caller has already found the run in the workspace. */
    @Transactional(readOnly = true)
    public List<RunQuestion> forRun(UUID orgId, UUID runId) {
        return questions.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(question -> orgId.equals(question.getOrgId()))
                .toList();
    }

    /**
     * Every pending question of a conversation, however old, plus the newest {@code limit} of any
     * status, oldest first. A pending question older than the newest page is never dropped.
     */
    @Transactional(readOnly = true)
    public List<RunQuestion> forConversation(UUID orgId, UUID conversationId, int limit) {
        Map<UUID, RunQuestion> byId = new LinkedHashMap<>();
        questions.findPendingForConversation(orgId, conversationId).forEach(q -> byId.put(q.getId(), q));
        questions
                .findRecentForConversation(orgId, conversationId, PageRequest.of(0, Math.max(limit, 1)))
                .forEach(q -> byId.putIfAbsent(q.getId(), q));
        List<RunQuestion> rows = new ArrayList<>(byId.values());
        rows.sort(Comparator.comparing(QuestionService::openedAtOrNull, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RunQuestion::getId));
        return rows;
    }

    /** Views for a batch, with goal titles and run statuses loaded in two queries in all. */
    @Transactional(readOnly = true)
    public List<QuestionView> views(List<RunQuestion> rows, Actor actor) {
        Set<UUID> goalIds = new HashSet<>();
        Set<UUID> runIds = new HashSet<>();
        for (RunQuestion row : rows) {
            if (row.getGoalId() != null) {
                goalIds.add(row.getGoalId());
            }
            runIds.add(row.getRunId());
        }
        Map<UUID, Goal> goalsById = new HashMap<>();
        if (!goalIds.isEmpty()) {
            goals.findAllById(goalIds).forEach(goal -> goalsById.put(goal.getId(), goal));
        }
        Map<UUID, Run> runsById = new HashMap<>();
        if (!runIds.isEmpty()) {
            runs.findAllById(runIds).forEach(run -> runsById.put(run.getId(), run));
        }

        List<QuestionView> views = new ArrayList<>(rows.size());
        for (RunQuestion row : rows) {
            Goal goal = goalsById.get(row.getGoalId());
            if (goal != null && !row.getOrgId().equals(goal.getOrgId())) {
                goal = null;
            }
            Run run = runsById.get(row.getRunId());
            String runStatus = run != null && row.getOrgId().equals(run.getOrgId()) ? run.getStatus() : "unknown";
            boolean extendable = row.isPending()
                    && !row.getExpiresAt().plus(window).isAfter(openedAt(row).plus(MAX_OPEN));
            views.add(new QuestionView(
                    row.getId(),
                    row.getRunId(),
                    row.getTaskId(),
                    row.getGoalId(),
                    row.getConversationId(),
                    row.getAgentId(),
                    goal == null ? null : goal.getTitle(),
                    row.getStatus(),
                    readMaps(row.getQuestionsJson()),
                    row.getAnswerJson() == null ? null : readAnswer(row),
                    row.getAnsweredBy(),
                    row.getAnsweredVia(),
                    row.getAnsweredAt(),
                    row.getRequestedBy(),
                    row.getCreatedAt(),
                    row.getExpiresAt(),
                    row.getClosedReason(),
                    canAnswer(row, actor),
                    extendable,
                    runStatus));
        }
        return views;
    }

    // ---- JSON -----------------------------------------------------------------------------------

    List<AskPersonTool.Question> readQuestions(RunQuestion question) {
        try {
            return json.readValue(question.getQuestionsJson(), QUESTIONS);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Question " + question.getId() + " holds questions that cannot be read", e);
        }
    }

    private List<Map<String, Object>> readMaps(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, MAPS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, Object> readAnswer(RunQuestion question) {
        try {
            return question.getAnswerJson() == null ? Map.of() : json.readValue(question.getAnswerJson(), MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> answerItems(Map<String, Object> answer) {
        if (!(answer.get("answers") instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<String> selectedOf(Map<String, Object> item) {
        if (!(item.get("selected") instanceof List<?> selected)) {
            return List.of();
        }
        return selected.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value instanceof String text) {
            node.put(field, text);
        } else {
            node.putNull(field);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A question could not be written as JSON", e);
        }
    }

    /** When the question was asked. A row not yet written has no created time, so its window stands in. */
    private Instant openedAt(RunQuestion question) {
        return question.getCreatedAt() != null
                ? question.getCreatedAt()
                : question.getExpiresAt().minus(window);
    }

    private static Instant openedAtOrNull(RunQuestion question) {
        return question.getCreatedAt();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
