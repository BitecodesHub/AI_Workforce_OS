// @find: platform core marker, component scanning, configuration properties scan, shared platform
// @what: Marker type services reference to scan the shared platform package.
package os.aiworkforce.platform;

/**
 * Marker for component scanning and configuration property scanning.
 *
 * <p>Services reference this type rather than a package name string, so moving a package cannot
 * silently disable half the platform's shared behaviour.
 *
 * <p>Used by the boot class of each service, for example {@code GatewayApplication}, in
 * {@code scanBasePackageClasses} and {@code basePackageClasses}. It holds no state and is never
 * instantiated.
 */
public final class PlatformCore {

    private PlatformCore() {}
}
