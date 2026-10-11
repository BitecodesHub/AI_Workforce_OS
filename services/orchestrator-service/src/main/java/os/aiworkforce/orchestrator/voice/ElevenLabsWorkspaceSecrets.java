// @find: elevenlabs key for phone calls, workspace secrets implementation, phone connector key lookup, ElevenLabsWorkspaceSecrets
// @what: Gives the built-in phone calls connector the workspace's stored ElevenLabs key, looked up in the organisation service at call time.
// @flow: Implements mcp-core WorkspaceSecrets; read by PhoneAdapter through SandboxServerRegistry; uses OrgCredentialResolver
package os.aiworkforce.orchestrator.voice;

import java.util.Optional;

import org.springframework.stereotype.Component;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.mcp.spi.WorkspaceSecrets;
import os.aiworkforce.orchestrator.service.OrgCredentialResolver;

/**
 * The same ElevenLabs key voice notes use, under {@code aiwos.voice.elevenlabs.credential-ref}.
 * "No key" and "the store did not answer" stay apart, so a store outage is never reported as a
 * missing key.
 */
@Component
public class ElevenLabsWorkspaceSecrets implements WorkspaceSecrets {

    private final OrgCredentialResolver credentials;
    private final VoiceProperties properties;

    /** Creates the provider with the credential resolver and the property naming the stored key. */
    public ElevenLabsWorkspaceSecrets(OrgCredentialResolver credentials, VoiceProperties properties) {
        this.credentials = credentials;
        this.properties = properties;
    }

    // @find: read elevenlabs key for phone call
    @Override
    public Optional<String> elevenLabsKey(String orgId) {
        CredentialResolver.Lookup lookup = credentials.lookup(orgId, properties.credentialRef());
        if (lookup instanceof CredentialResolver.Found found) {
            return Optional.of(found.value());
        }
        if (lookup instanceof CredentialResolver.Unavailable unavailable) {
            throw new IllegalStateException("The credential store did not answer: " + unavailable.reason());
        }
        return Optional.empty();
    }
}
