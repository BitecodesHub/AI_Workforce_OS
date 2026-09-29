package os.aiworkforce.analytics.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.repository.AuditEvents;
import os.aiworkforce.analytics.service.AuditChain;
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
 * <p>The chain is one sequence for the whole platform (see {@link AuditEvents#findFirstByOrderBySequenceDesc()}),
 * so the entry this call appends is always chained onto the single most recently written row,
 * regardless of which organisation either belongs to.
 *
 * <p>The read-then-insert here is not fully race-free across multiple instances of this service:
 * two concurrent calls could both read the same "most recent" row and each compute a hash from
 * it, and only one of the resulting rows would end up truly chained. The {@code synchronized}
 * keyword below closes that window within one process, which is what this platform runs today;
 * a deployment with more than one instance of analytics-service would need a database-level
 * serialisation point (an advisory lock, or a single-row sequence table locked {@code FOR
 * UPDATE}) to close it across processes too.
 */
@RestController
@RequestMapping("/internal/audit-events")
@Tag(name = "Internal")
public class InternalAuditController {

    private static final List<String> VALID_OUTCOMES = List.of("succeeded", "failed", "denied");

    private final AuditEvents events;
    private final AuditChain chain;
    private final ObjectMapper objectMapper;

    public InternalAuditController(AuditEvents events, AuditChain chain, ObjectMapper objectMapper) {
        this.events = events;
        this.chain = chain;
        this.objectMapper = objectMapper;
    }

    public record AuditEventRequest(
            UUID orgId,
            @NotBlank String actorId,
            @NotBlank String actorKind,
            String onBehalfOf,
            @NotBlank String action,
            @NotBlank String resourceType,
            String resourceId,
            @NotBlank String outcome,
            Map<String, Object> detail,
            String requestId) {}

    public record AuditEventResponse(UUID id, long sequence, String entryHash) {}

    @PostMapping
    @Operation(summary = "Internal: append one entry to the append-only audit projection")
    @Transactional
    public synchronized AuditEventResponse append(@Valid @RequestBody AuditEventRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        if (!VALID_OUTCOMES.contains(request.outcome())) {
            throw ApiException.validation("outcome", "must be one of " + VALID_OUTCOMES);
        }

        AuditEvent previous = events.findFirstByOrderBySequenceDesc().orElse(null);
        String previousHash = previous == null ? null : previous.getEntryHash();

        Map<String, Object> detail = request.detail() == null ? Map.of() : request.detail();
        Instant occurredAt = Instant.now();

        String entryHash = chain.hash(
                previousHash,
                request.actorId(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.outcome(),
                occurredAt.toString(),
                canonicalJson(detail));

        AuditEvent event = new AuditEvent();
        event.setOrgId(request.orgId());
        event.setActorId(request.actorId());
        event.setActorKind(request.actorKind());
        event.setOnBehalfOf(request.onBehalfOf());
        event.setAction(request.action());
        event.setResourceType(request.resourceType());
        event.setResourceId(request.resourceId());
        event.setOutcome(request.outcome());
        event.setDetail(detail);
        event.setRequestId(request.requestId());
        event.setOccurredAt(occurredAt);
        event.setPreviousHash(previousHash);
        event.setEntryHash(entryHash);
        events.save(event);

        return new AuditEventResponse(event.getId(), event.getSequence(), event.getEntryHash());
    }

    /**
     * A key-sorted rendering of the detail map, so the hash does not depend on whichever
     * iteration order the caller's map happened to have.
     */
    private String canonicalJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(detail));
        } catch (Exception e) {
            throw ApiException.internal("Could not serialise the audit entry's detail", e);
        }
    }
}
