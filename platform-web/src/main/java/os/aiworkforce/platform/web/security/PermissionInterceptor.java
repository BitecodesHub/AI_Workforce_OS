// @find: permission interceptor, enforce requires permission, 403 forbidden, 401 unauthenticated, workspace in scope, rbac enforcement
// @what: Enforces @RequiresPermission on every controller method that carries it.
// @flow: Registered by WebMvcConfig; reads Actor from RequestContext
package os.aiworkforce.platform.web.security;

import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Enforces {@link RequiresPermission} on every controller method that carries it.
 *
 * <p>This runs in each service, not only at the gateway. The gateway can be bypassed by anything
 * already inside the cluster, so an edge check alone is a convenience, never a control.
 *
 * <p>Three things are checked, in order, because each is meaningless without the one before it:
 * that somebody is authenticated, that an organisation is in scope, and that the permission is
 * held. Checking the permission of an actor with no workspace would pass a token issued for a
 * different workspace entirely.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (required == null) {
            return true;
        }

        Actor actor = RequestContext.requireActor();

        if (!required.allowWithoutOrganisation() && actor.orgId() == null) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "No workspace is selected for this session.");
        }

        String[] codes = required.value();
        boolean permitted = required.mode() == RequiresPermission.Mode.ALL
                ? Arrays.stream(codes).allMatch(actor::hasPermission)
                : Arrays.stream(codes).anyMatch(actor::hasPermission);

        if (!permitted) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED)
                    .with("requiredPermissions", Arrays.asList(codes))
                    .with("mode", required.mode().name().toLowerCase(java.util.Locale.ROOT));
        }

        return true;
    }

    /**
     * Refuses a permission code the build does not define.
     *
     * <p>Called at startup by {@code PermissionAnnotationValidator}. A typo in an annotation would
     * otherwise produce a check that nobody can ever satisfy - an endpoint quietly unusable by
     * everyone, which is a much harder failure to notice than one that is quietly open.
     */
    public static void validate(String[] codes) {
        for (String code : codes) {
            if (!Permission.isKnown(code)) {
                throw new IllegalStateException("@RequiresPermission references unknown permission code: " + code);
            }
        }
    }
}
