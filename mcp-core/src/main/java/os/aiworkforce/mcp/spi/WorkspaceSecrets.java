// @find: workspace secrets, elevenlabs key lookup for phone calls, voice key, built in connector credential, WorkspaceSecrets
// @what: Interface a service implements so a built-in connector can read a workspace's stored ElevenLabs key at call time.
// @flow: Implemented in the orchestrator over the organisation service's credential store; read by PhoneAdapter
package os.aiworkforce.mcp.spi;

import java.util.Optional;

/**
 * The workspace keys a built-in connector needs, looked up immediately before the call that uses
 * them and never kept.
 *
 * <p>Lives behind an interface so {@code mcp-core} never touches the credential store itself.
 */
public interface WorkspaceSecrets {

    /**
     * The workspace's ElevenLabs key, or empty when none is stored.
     *
     * @throws IllegalStateException when the store could not be asked, which is not the same as no key
     */
    Optional<String> elevenLabsKey(String orgId);
}
