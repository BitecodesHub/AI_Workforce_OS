// @find: audit token, service token for analytics, internal token, relay credential
// @what: Interface that supplies the service token the relay presents to analytics-service.
// @flow: Implemented per service via its internal token provider
package os.aiworkforce.platform.web.audit;

/**
 * Where a service gets the token it presents to analytics-service.
 *
 * <p>Each service already has its own way: identity signs one itself, the others ask identity for
 * one through their {@code InternalTokenProvider}. The relay runs on a scheduler thread, outside
 * any request, so the token it needs names the platform rather than a person; the person an event
 * is about travels in the event.
 */
@FunctionalInterface
public interface AuditTokenSource {

    /** A bearer token accepted by analytics-service's internal endpoints. */
    String analyticsToken();
}
