package os.aiworkforce.llm;

/**
 * Marker for component scanning.
 *
 * <p>The provider adapters and the router are Spring beans, and a service that needs them has to
 * say so. Referencing this type rather than a package name string means moving a package cannot
 * silently leave a service without a model layer.
 */
public final class LlmCore {

    private LlmCore() {}
}
