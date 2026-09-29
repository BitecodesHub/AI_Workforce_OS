package os.aiworkforce.analytics.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.repository.AuditEvents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The audit log, as a workspace sees it.
 *
 * <p>Every entry names the person accountable for it - {@code onBehalfOf} is populated whenever
 * an agent or a service acted for somebody - because a trail that stops at "the orchestrator did
 * it" cannot answer the only question ever asked of one.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit")
public class AuditController {

    private final AuditEvents events;

    public AuditController(AuditEvents events) {
        this.events = events;
    }

    public record AuditEventView(
            UUID id,
            long sequence,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail,
            Instant occurredAt) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.AUDIT_READ)
    @Operation(summary = "This workspace's audit entries, newest first")
    public List<AuditEventView> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return events.findByOrgIdOrderBySequenceDesc(orgId(), PageRequest.of(page, Math.min(size, 100)))
                .map(AuditController::toView)
                .toList();
    }

    private static AuditEventView toView(AuditEvent event) {
        return new AuditEventView(
                event.getId(),
                event.getSequence(),
                event.getActorId(),
                event.getActorKind(),
                event.getOnBehalfOf(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getDetail(),
                event.getOccurredAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
