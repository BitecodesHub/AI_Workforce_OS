package os.aiworkforce.llm.spi;

import java.util.Optional;

/**
 * Fetches and decrypts a provider credential.
 *
 * <p>Kept behind an interface so {@code llm-core} never touches the credential store, and so the
 * decrypted value exists only for the duration of one call. A credential is resolved at the last
 * possible moment, immediately before the request is signed, and is never cached in a field, put
 * on a log line, or attached to an attempt record.
 */
public interface CredentialResolver {

    /**
     * The credential a provider reference points at, decrypted.
     *
     * <p>Empty means the provider is not configured for this workspace, which is a skip rather
     * than a failure: the router moves to the next candidate and records why.
     */
    Optional<String> resolve(String orgId, String credentialRef);
}
