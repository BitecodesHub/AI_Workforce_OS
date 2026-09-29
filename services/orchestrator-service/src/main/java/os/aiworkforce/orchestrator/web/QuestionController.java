package os.aiworkforce.orchestrator.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Questions agents asked while working, and the answers people give.
 *
 * <p>An answer is accepted here and the run resumes on a thread of its own, so the person gets a
 * reply at once rather than waiting for the agent's next turn; the run's progress is then seen the
 * ordinary way. The resume is submitted only after the answer has committed, and only for an
 * answer that is new: the same person repeating the same answer is told it was received, and
 * nothing resumes twice.
 */
@RestController
@RequestMapping("/api/orchestrator/questions")
@Tag(name = "Questions")
public class QuestionController {

    private final QuestionService questions;
    private final RunExecutor executor;
    private final Runs runs;

    public QuestionController(QuestionService questions, RunExecutor executor, Runs runs) {
        this.questions = questions;
        this.executor = executor;
        this.runs = runs;
    }

    public record AnswerItemRequest(
            @NotBlank @Size(max = 8) String questionId,
            @Size(max = 4) List<@NotBlank @Size(max = 60) String> selected,
            @Size(max = 2_000) String other) {}

    public record AnswerRequest(
            @Size(max = 4) List<@Valid AnswerItemRequest> answers,
            @Size(max = 2_000) String note,
            boolean skipped,
            @NotBlank @Pattern(regexp = "chat|orchestrator|run|approvals") String via) {}

    /** @param runStatus the run's status as the answer is accepted, usually still waiting_input */
    public record AnswerResult(QuestionService.QuestionView question, String runStatus) {}

    /**
     * Pending questions, the soonest to close first, or every question newest first.
     *
     * @param mine only the questions asked of the caller: those whose work they asked for
     */
    @GetMapping
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Questions agents asked in this workspace")
    public List<QuestionService.QuestionView> list(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "50") int limit) {
        UUID orgId = orgId();
        String wanted = status == null ? "pending" : status.strip().toLowerCase(Locale.ROOT);
        if (!"pending".equals(wanted) && !"all".equals(wanted)) {
            throw ApiException.validation("status", "must be pending or all");
        }
        int size = Math.clamp(limit, 1, 100);
        Actor actor = RequestContext.requireActor();
        List<RunQuestion> rows =
                "pending".equals(wanted) ? questions.pending(orgId, size) : questions.recent(orgId, size);
        if (mine) {
            String me = actor.humanId();
            rows = rows.stream()
                    .filter(row -> row.getRequestedBy() != null
                            && row.getRequestedBy().toString().equals(me))
                    .toList();
        }
        return questions.views(rows, actor);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "One question")
    public QuestionService.QuestionView get(@PathVariable UUID id) {
        RunQuestion question = questions.find(orgId(), id).orElseThrow(() -> ApiException.notFound("question", id));
        return questions.views(List.of(question), RequestContext.requireActor()).getFirst();
    }

    @PostMapping("/{id}/answer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(
            value = {Permission.Codes.TASK_CREATE, Permission.Codes.AGENT_RUN},
            mode = RequiresPermission.Mode.ANY)
    @Operation(summary = "Answer a question, and let the run that asked it continue")
    public AnswerResult answer(@PathVariable UUID id, @Valid @RequestBody AnswerRequest request) {
        // Commits on return, so the resume below reads the answer from the database.
        QuestionService.AnswerOutcome outcome = questions.answer(orgId(), id, toInput(request));
        RunQuestion answered = outcome.question();
        if (outcome.newlyAnswered()) {
            executor.submitResume(answered.getOrgId(), answered.getRunId());
        }
        String runStatus =
                runs.findById(answered.getRunId()).map(Run::getStatus).orElse("unknown");
        return new AnswerResult(
                questions
                        .views(List.of(answered), RequestContext.requireActor())
                        .getFirst(),
                runStatus);
    }

    @PostMapping("/{id}/extend")
    @RequiresPermission(
            value = {Permission.Codes.TASK_CREATE, Permission.Codes.AGENT_RUN},
            mode = RequiresPermission.Mode.ANY)
    @Operation(summary = "Keep a question open for longer, up to seven days from when it was asked")
    public QuestionService.QuestionView extend(@PathVariable UUID id) {
        RunQuestion extended = questions.extend(orgId(), id);
        return questions.views(List.of(extended), RequestContext.requireActor()).getFirst();
    }

    private static QuestionService.AnswerInput toInput(AnswerRequest request) {
        List<QuestionService.AnswerItem> items = request.answers() == null
                ? List.of()
                : request.answers().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(item -> new QuestionService.AnswerItem(item.questionId(), item.selected(), item.other()))
                        .toList();
        return new QuestionService.AnswerInput(items, request.note(), request.skipped(), request.via());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
