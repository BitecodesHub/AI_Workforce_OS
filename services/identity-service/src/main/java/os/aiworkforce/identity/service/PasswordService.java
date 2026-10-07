package os.aiworkforce.identity.service;

import java.util.regex.Pattern;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Hashes and checks passwords.
 *
 * <p>Argon2id rather than bcrypt: it is memory-hard, so the advantage a purpose-built cracking
 * rig holds over a general-purpose server is far smaller. The cost parameters are configuration,
 * and because each hash records the parameters it was made with, raising them later does not
 * invalidate a single existing password.
 */
@Service
public class PasswordService {

    /*
     * Length is the requirement; composition rules are not. Forcing a symbol and a digit reliably
     * produces "Password1!" - predictable to a cracker and irritating to a person - while a long
     * passphrase is both easier to remember and far harder to guess.
     */
    private static final Pattern WHITESPACE_ONLY = Pattern.compile("^\\s*$");

    private final Argon2PasswordEncoder encoder;
    private final int minimumLength;

    /*
     * A real hash made with the configured parameters, for wasteTime to verify against. A fixed
     * literal carries its own parameters, so whenever the configuration differed from them a
     * missing or locked account took measurably more or less time than a wrong password.
     */
    private final String timingHash;

    public PasswordService(PlatformProperties properties) {
        PlatformProperties.Argon2 argon2 = properties.security().argon2();
        this.encoder = new Argon2PasswordEncoder(
                argon2.saltLength(),
                argon2.hashLength(),
                argon2.parallelism(),
                argon2.memoryKib(),
                argon2.iterations());
        this.minimumLength = properties.security().passwordMinLength();
        this.timingHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public String hash(String rawPassword) {
        validate(rawPassword);
        return encoder.encode(rawPassword);
    }

    /**
     * Checks a password against a stored hash.
     *
     * <p>Returns false rather than throwing for a missing hash, so an account that has only ever
     * signed in through Google takes the same code path, and the same amount of time, as one with
     * a wrong password. A caller that can distinguish the two has a way to enumerate accounts.
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (storedHash == null || rawPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }

    /** Whether a hash was made with weaker parameters than the current configuration. */
    public boolean needsRehash(String storedHash) {
        return storedHash != null && encoder.upgradeEncoding(storedHash);
    }

    private void validate(String rawPassword) {
        if (rawPassword == null || WHITESPACE_ONLY.matcher(rawPassword).matches()) {
            throw ApiException.validation("password", "must not be empty");
        }
        if (rawPassword.length() < minimumLength) {
            throw ApiException.validation("password", "must be at least " + minimumLength + " characters");
        }
        // Argon2 accepts any length, but an unbounded password is an unbounded amount of hashing
        // work that an anonymous caller gets to choose.
        if (rawPassword.length() > 256) {
            throw ApiException.validation("password", "must be 256 characters or fewer");
        }
    }

    /**
     * Spends the time one password check takes, without checking anything.
     *
     * <p>Called wherever a sign-in is refused before the password is compared - no such account,
     * or one that is locked or disabled - so the response takes as long as a wrong password and
     * timing does not reveal which addresses exist or which accounts are locked.
     */
    public void wasteTime() {
        encoder.matches("not-a-real-password", timingHash);
    }

    static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
}
