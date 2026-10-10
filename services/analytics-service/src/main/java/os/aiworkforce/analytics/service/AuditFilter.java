// @find: audit filter, filter audit log, actors, actions, date range, resource, outcome, search criteria
// @what: Holds the optional filters an auditor narrows the audit log by.
// @flow: Built by AuditController; used by AuditSearch
package os.aiworkforce.analytics.service;

import java.time.Instant;
import java.util.List;

/**
 * What an auditor narrows the log by. Every field is optional; the workspace is not here because it
 * is never optional - it comes from the caller's token, not from the request.
 *
 * @param actors who or what acted: a person's id, an agent's, or a service's
 * @param onBehalfOf the person an agent or service acted for
 * @param actions one or more action codes such as {@code member.role_change}
 * @param from entries at or after this instant
 * @param to entries before this instant
 */
public record AuditFilter(
        String actorId,
        String onBehalfOf,
        List<String> actions,
        String resourceType,
        String resourceId,
        String outcome,
        Instant from,
        Instant to) {

    public static final AuditFilter NONE = new AuditFilter(null, null, List.of(), null, null, null, null, null);

    public AuditFilter {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
