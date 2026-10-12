// @find: member permissions lookup, who may decide, identity permissions for push, person permission codes, MemberPermissions
// @what: Seam that answers which permission codes a person holds in a workspace right now, or that they are not a member.
// @flow: Implemented by IdentityMemberPermissions; used by PushRecipients to choose who hears about an approval or a question
package os.aiworkforce.orchestrator.push;

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
}
