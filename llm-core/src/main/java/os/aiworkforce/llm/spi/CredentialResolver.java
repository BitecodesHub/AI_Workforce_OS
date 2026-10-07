package os.aiworkforce.llm.spi;

import java.util.Objects;
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
     * <p>Empty means there is no value to use, for whatever reason. Callers that must tell a
     * missing key from a store that could not be reached use {@link #lookup} instead.
     */
    Optional<String> resolve(String orgId, String credentialRef);

    /**
     * The credential, or why there is none.
     *
     * <p>"Nothing is stored" and "the store did not answer" call for different responses: the
     * first is a skip that only a person storing a key can fix, the second is a blip that heals
     * on its own. Reporting a blip as a missing key sends administrators to re-enter keys that
     * were never wrong. An implementation that can tell them apart overrides this; the default
     * can only say what {@link #resolve} said, so it never reports {@link Unavailable}.
     */
    default Lookup lookup(String orgId, String credentialRef) {
        return resolve(orgId, credentialRef)
                .filter(value -> !value.isBlank())
                .<Lookup>map(Found::new)
                .orElse(NotFound.INSTANCE);
    }

    /** What a lookup found. */
    sealed interface Lookup permits Found, NotFound, Unavailable {}

    /** A stored, decrypted value. Never logged and never put on a record. */
    record Found(String value) implements Lookup {

        public Found {
            Objects.requireNonNull(value, "value");
        }

        /** The value is deliberately kept out of the text form, which ends up in logs. */
        @Override
        public String toString() {
            return "Found[value=<redacted>]";
        }
    }

    /** The store answered, and nothing usable is stored for this reference. */
    enum NotFound implements Lookup {
        INSTANCE
    }

    /**
     * The store could not be asked, or did not answer.
     *
     * @param reason what went wrong, for the log; never shown to a person and never a secret
     */
    record Unavailable(String reason) implements Lookup {}
}
