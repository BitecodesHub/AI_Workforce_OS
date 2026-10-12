// @find: session liveness, is this device still signed in, signed out device push, revoked session push, sign out everywhere push, SessionLiveness
// @what: Seam that answers which identity sessions still belong to a signed-in device, so push stops reaching a device whose sign-in ended.
// @flow: Implemented by IdentitySessionLiveness; used by PushService before every delivery and the test button
package os.aiworkforce.orchestrator.push;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether the device behind a subscription is still signed in.
 *
 * <p>Each subscription remembers the identity session that saved it. Signing that device out -
 * from the device itself, from another device's Profile page, by signing out everywhere or by
 * changing the password - ends the session's family, and the device must stop receiving the
 * workspace's notifications at once, even though the browser still holds its subscription.
 */
public interface SessionLiveness {

    /**
     * The ones among {@code sessionIds} whose sign-in is still live.
     *
     * @return the live ones (possibly none); an empty {@link Optional} when the answer could not be
     *     had, in which case the caller sends nothing and removes nothing
     */
    Optional<Set<UUID>> live(Collection<UUID> sessionIds);
}
