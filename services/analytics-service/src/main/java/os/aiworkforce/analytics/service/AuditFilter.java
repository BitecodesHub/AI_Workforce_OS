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
 * <p>Built by AuditController from the request parameters and consumed by {@link AuditSearch}.
 * Fields left null (or an empty action list) do not narrow the search.
 *
 * @param actorId who or what acted: a person's id, an agent's, or a service's
 * @param onBehalfOf the person an agent or service acted for
 * @param actions one or more action codes such as {@code member.role_change}
 * @param resourceType the kind of thing acted on, such as an agent or a member
 * @param resourceId the specific thing acted on
 * @param outcome succeeded, failed, denied or locked
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

    /** The filter that narrows nothing, used when the caller supplies no criteria. */
    public static final AuditFilter NONE = new AuditFilter(null, null, List.of(), null, null, null, null, null);

    /**
     * Compact constructor: replaces a null action list with an empty list and stores an immutable
     * copy, so {@link AuditSearch} can call {@code actions()} without a null check.
     */
    public AuditFilter {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
