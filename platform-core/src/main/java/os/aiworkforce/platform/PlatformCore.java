// @find: platform core marker, component scanning, configuration properties scan, shared platform
// @what: Marker type services reference to scan the shared platform package.
package os.aiworkforce.platform;

/**
 * Marker for component scanning and configuration property scanning.
 *
 * <p>Services reference this type rather than a package name string, so moving a package cannot
 * silently disable half the platform's shared behaviour.
 */
public final class PlatformCore {

    private PlatformCore() {}
}
