// @find: member permissions lookup, who may decide, identity permissions for push, person permission codes, batch permissions, MemberPermissions
// @what: Seam that answers which permission codes people hold in a workspace right now, one person or many at once, or that they are not members.
// @flow: Implemented by IdentityMemberPermissions; used by PushRecipients to choose who hears about an approval or a question
package os.aiworkforce.orchestrator.push;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a person holds in a workspace, asked of the service that owns memberships.
 *
 * <p>Push recipients are chosen without anybody's request in flight, so the person's own token is
 * not there to say what they may do; this asks instead. A person who is no longer a member holds
 * nothing.
 */
public interface MemberPermissions {

    /**
     * The permission codes the person holds through their membership.
     *
     * @return the codes, empty for a person who is not an active member; empty {@link Optional}
     *     when the answer could not be had (the caller then leaves that person out)
     */
    Optional<Set<String>> permissionsOf(UUID orgId, UUID userId);

    // @find: batch member permissions
    /**
     * The permission codes of several people at once. Each person the answer covers is in the map
     * (with an empty set when they are not a member); a person whose answer could not be had is
     * missing from it, and is left out by the caller. This default asks one person at a time; the
     * identity implementation asks once for the lot.
     */
    default Map<UUID, Set<String>> permissionsOf(UUID orgId, Collection<UUID> userIds) {
        Map<UUID, Set<String>> held = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            permissionsOf(orgId, userId).ifPresent(codes -> held.put(userId, codes));
        }
        return held;
    }
}
