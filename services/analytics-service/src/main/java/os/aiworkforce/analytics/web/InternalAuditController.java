package os.aiworkforce.analytics.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.analytics.service.AuditAppender;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Appends one entry to the audit projection.
 *
 * <p>Internal only, and refused to a person's own token, the same as every other internal
 * endpoint on this platform: this is the one place that can put words in the audit log, and a
 * person presenting their own credentials must never be able to write an entry by calling it
 * directly - only a sibling service, through the internal token flow, may.
 *
 * <p>The entry joins the chain of the workspace it names, or the platform chain when it names none.
 * {@link AuditAppender} serialises appends to one chain in the database, so any number of instances
 * of this service can take calls at once. Sending the same {@code eventId} twice appends once: the
 * second call is answered with the first call's entry, which is what lets a sender retry a
 * delivery it does not know the outcome of.
 */
@RestController
@RequestMapping("/internal/audit-events")
@Tag(name = "Internal")
public class InternalAuditController {

    private static final List<String> VALID_OUTCOMES = List.of("succeeded", "failed", "denied", "locked");

    private final AuditAppender appender;

    public InternalAuditController(AuditAppender appender) {
        this.appender = appender;
    }

    /**
     * @param eventId the sender's id for this event, fixed before its first attempt; optional, but a
     *     sender that retries must send one
     * @param occurredAt when the action happened, as the sender saw it; optional, and ignored when it
     *     is further in the future than a clock difference explains
     */
    public record AuditEventRequest(
            UUID orgId,
            @NotBlank @Size(max = 200) String actorId,
            @NotBlank @Size(max = 40) String actorKind,
            @Size(max = 200) String onBehalfOf,
            @NotBlank @Size(max = 120) String action,
            @NotBlank @Size(max = 80) String resourceType,
            @Size(max = 200) String resourceId,
            @NotBlank String outcome,
            Map<String, Object> detail,
            @Size(max = 128) String requestId,
            UUID eventId,
            Instant occurredAt) {}

    public record AuditEventResponse(UUID id, long sequence, String entryHash) {}

    @PostMapping
    @Operation(summary = "Internal: append one entry to the append-only audit projection")
    public AuditEventResponse append(@Valid @RequestBody AuditEventRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        if (!VALID_OUTCOMES.contains(request.outcome())) {
            throw ApiException.validation("outcome", "must be one of " + VALID_OUTCOMES);
        }

        AuditAppender.Appended appended = appender.append(new AuditAppender.Command(
                request.eventId(),
                request.orgId(),
                request.actorId(),
                request.actorKind(),
                request.onBehalfOf(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.outcome(),
                request.detail(),
                request.requestId(),
                request.occurredAt()));
        return new AuditEventResponse(appended.id(), appended.sequence(), appended.entryHash());
    }
}
