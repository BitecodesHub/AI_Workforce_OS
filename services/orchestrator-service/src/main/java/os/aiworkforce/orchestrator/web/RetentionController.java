// @find: retention settings, how long run detail is kept, data retention days, /api/orchestrator/retention-settings, Privacy settings
// @what: REST endpoints to read and change how many days run detail is kept.
// @flow: Delegates to RetentionService
package os.aiworkforce.orchestrator.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.RetentionService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * How long this workspace keeps what its agents saw and did.
 *
 * <p>One number: the days a finished run keeps the text of its steps, what each tool returned and
 * what it was asked (see {@link RetentionService}). Changing it needs {@code workspace:update},
 * like the rest of the workspace's settings, and is written to the audit log with the old and new
 * values, because shortening it makes the next night's purge remove text that can then not be read
 * again.
 */
@RestController
@RequestMapping("/api/orchestrator/retention-settings")
@Tag(name = "Retention")
public class RetentionController {

    private final RetentionService retention;
    private final AuditClient audit;

    public RetentionController(RetentionService retention, AuditClient audit) {
        this.retention = retention;
        this.audit = audit;
    }

    /**
     * @param runDetailDays how many days a finished run keeps the text of its steps
     * @param defaultRunDetailDays what it is until a workspace chooses
     * @param minRunDetailDays the shortest that may be chosen
     * @param maxRunDetailDays the longest that may be chosen
     * @param usageDays how long usage figures behind the spend reports are kept, which is not a setting
     */
    public record SettingsView(
            int runDetailDays, int defaultRunDetailDays, int minRunDetailDays, int maxRunDetailDays, int usageDays) {}

    /** @param runDetailDays the new number of days; the range is checked here and again by the setting itself */
    public record UpdateRequest(
            @NotNull @Min(7) @Max(3650) Integer runDetailDays) {}

    // @find: get retention settings, GET /api/orchestrator/retention-settings
    @GetMapping
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "How long this workspace keeps the detail of finished runs")
    public SettingsView get() {
        return view(retention.runDetailDays(orgId()));
    }

    // @find: update retention days, PUT /api/orchestrator/retention-settings
    @PutMapping
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Set how long this workspace keeps the detail of finished runs")
    public SettingsView update(@Valid @RequestBody UpdateRequest request) {
        UUID orgId = orgId();
        Actor actor = RequestContext.requireActor();
        int before = retention.runDetailDays(orgId);
        retention.setRunDetailDays(orgId, request.runDetailDays(), actor.id());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runDetailDays", request.runDetailDays());
        detail.put("previousRunDetailDays", before);
        audit.record(orgId, actor, "retention.update", "workspace", orgId.toString(), "succeeded", detail);
        return view(retention.runDetailDays(orgId));
    }

    private static SettingsView view(int days) {
        return new SettingsView(
                days,
                RetentionService.DEFAULT_RUN_DETAIL_DAYS,
                RetentionService.min(),
                RetentionService.max(),
                RetentionService.USAGE_DAYS);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
