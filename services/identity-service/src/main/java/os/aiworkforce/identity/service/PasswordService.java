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

    public PasswordService(PlatformProperties properties) {
        PlatformProperties.Argon2 argon2 = properties.security().argon2();
        this.encoder = new Argon2PasswordEncoder(
                argon2.saltLength(),
                argon2.hashLength(),
                argon2.parallelism(),
                argon2.memoryKib(),
                argon2.iterations());
        this.minimumLength = properties.security().passwordMinLength();
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

    /** Spends comparable time when no account exists, so timing does not reveal which emails do. */
    public void wasteTime() {
        encoder.matches(
                "not-a-real-password",
                "$argon2id$v=19$m=65536,t=3,p=4$"
                        + "c29tZXNhbHR2YWx1ZQ$UqE5Y3JhY2tpbmdpc3Nsb3d3aXRoYXJnb24yaWQxMjM0NTY");
    }

    static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
}
