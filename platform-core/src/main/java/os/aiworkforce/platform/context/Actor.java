// @find: actor, who is acting, user, api key, agent, system, on behalf of, accountable person, permissions in token, organisation id
// @what: Describes who is responsible for the current work: person, key, agent or system, and the person an agent acts for.
// @flow: Built by JwtActorConverter; held in RequestContext; recorded in audit events
package os.aiworkforce.platform.context;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who is responsible for the current unit of work.
 *
 * <p>{@code kind} separates a signed-in person from a machine key and from an agent acting on a
 * person's behalf. An agent always carries {@link #onBehalfOf()}, so no automated action can
 * appear in the audit trail without the human accountable for it.
 *
 * @param id identifier of the acting principal
 * @param kind {@code user}, {@code apiKey}, {@code agent} or {@code system}
 * @param orgId organisation this work belongs to; {@code null} only for platform-level work
 * @param roleId the membership role that granted the permission set
 * @param permissions permission codes carried by the token, already resolved from the role
 * @param permissionVersion counter that invalidates a token when a role changes
 * @param onBehalfOf the human an agent or machine key is acting for
 * @param sessionId session that issued the token, for revocation
 * @param agentId the agent executing, when {@code kind} is {@code agent}
 * @param claims remaining token claims, for auditing rather than for decisions
 */
public record Actor(
        String id,
        Kind kind,
        String orgId,
        String roleId,
        Set<String> permissions,
        long permissionVersion,
        String onBehalfOf,
        String sessionId,
        String agentId,
        Map<String, Object> claims) {

    /**
     * The category of principal, which decides how the work is attributed and audited.
     */
    public enum Kind {
        USER,
        API_KEY,
        AGENT,
        SYSTEM
    }

    /** The platform itself, used by schedulers and migrations. Holds no permissions. */
    public static final Actor SYSTEM =
            new Actor("system", Kind.SYSTEM, null, null, Set.of(), 0L, null, null, null, Map.of());

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        claims = claims == null ? Map.of() : Collections.unmodifiableMap(Map.copyOf(claims));
    }

    /**
     * Builds a signed-in person from token claims.
     *
     * <p>Used by {@code JwtActorConverter} and by tests. The result has no delegation, session or agent
     * identifier.
     */
    public static Actor user(String id, String orgId, String roleId, Set<String> permissions, long version) {
        return new Actor(id, Kind.USER, orgId, roleId, permissions, version, null, null, null, Map.of());
    }

    /**
     * Whether an agent is executing, rather than a person or a machine key.
     */
    public boolean isAgent() {
        return kind == Kind.AGENT;
    }

    /**
     * Whether the platform itself is executing, for example a scheduler.
     */
    public boolean isSystem() {
        return kind == Kind.SYSTEM;
    }

    /** The person accountable for this work, whoever or whatever is executing it. */
    public String humanId() {
        if (onBehalfOf != null) {
            return onBehalfOf;
        }
        return kind == Kind.USER ? id : null;
    }

    /**
     * Derives the agent actor that runs work for this principal.
     *
     * <p>The agent inherits the organisation but not the permission set: an agent's authority
     * comes from its own tool grants, never from the person who started the run. That is what
     * stops a manager's session from turning into an agent with a manager's reach.
     */
    public Actor asAgent(String agentId, Set<String> agentPermissions) {
        return new Actor(
                agentId,
                Kind.AGENT,
                orgId,
                null,
                agentPermissions,
                permissionVersion,
                humanId() != null ? humanId() : id,
                sessionId,
                agentId,
                Map.of());
    }

    /**
     * Whether the token carries exactly this permission code.
     *
     * <p>An exact set lookup: there are no wildcards, and organisation ownership is checked separately
     * by the permission interceptor in platform-web.
     */
    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }
}
