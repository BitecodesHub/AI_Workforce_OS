package os.aiworkforce.platform.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission a handler needs.
 *
 * <p>Put this on the controller method, not only on the route in the gateway. The gateway's check
 * is a coarse first pass; anything inside the cluster can reach a service directly, so the service
 * that owns the data is the only place an authorisation decision is trustworthy.
 *
 * <p>The interceptor also re-checks that the resource belongs to the caller's organisation, which
 * is the half of authorisation a permission code alone cannot express: holding {@code agent:read}
 * is not permission to read <em>another workspace's</em> agent.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** One or more permission codes, for example {@code agent:update}. */
    String[] value();

    /** Whether every listed code is required, or any one of them suffices. */
    Mode mode() default Mode.ANY;

    /**
     * Whether the handler may run without an organisation in scope.
     *
     * <p>Almost nothing should: platform-level endpoints are the exception, and marking one is a
     * deliberate statement that the handler scopes its own data.
     */
    boolean allowWithoutOrganisation() default false;

    enum Mode {
        ANY,
        ALL
    }
}
