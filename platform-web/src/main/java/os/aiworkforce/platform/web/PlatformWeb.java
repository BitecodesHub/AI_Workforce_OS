// @find: platform web marker, component scanning, servlet services, shared web beans
// @what: Marker type that makes the seven servlet services scan the shared web package.
package os.aiworkforce.platform.web;

/**
 * Marker for component scanning in the seven servlet services.
 *
 * <p>The gateway is reactive and deliberately does not depend on this module.
 */
public final class PlatformWeb {

    /** Not instantiable: the type exists only so that a component scan can name this package. */
    private PlatformWeb() {}
}
