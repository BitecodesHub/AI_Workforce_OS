// @find: secret guard, refuse secrets in memory, detect password, api key, card number, looks secret, sensitive text
// @what: Detects text that looks like a password, key or card number so it is never stored as a memory.
// @flow: Used by AgentMemoryService
package os.aiworkforce.memory.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognises text that looks like a password, a key or a card number, so it is never stored as a
 * memory. A memory is read back into later prompts and shown to anyone who can read the agent, so
 * a secret kept there would be spread to every run and every reader.
 *
 * <p>It errs towards refusing: a person can rephrase a note, and cannot take back a leaked key.
 */
public final class SecretGuard {

    /**
     * Regular expressions for secret-looking text: keyword-introduced values, well-known key prefixes, private key headers, tokens and long unbroken key-like runs. Any single match counts.
     */
    private static final List<Pattern> PATTERNS = List.of(
            // Words that introduce a secret value: "the password is hunter2", "api key: abc".
            Pattern.compile(
                    "(?i)\\b(password|passcode|passphrase|secret|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|"
                            + "private[ _-]?key|client[ _-]?secret|bearer)\\b\\s*(is|=|:)\\s*\\S{4,}"),
            // Well-known key shapes.
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bgsk_[A-Za-z0-9]{16,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            // A long unbroken run of key-like characters.
            Pattern.compile("\\b[A-Za-z0-9_-]{40,}\\b"));

    /**
     * Candidate card numbers: 13 to 19 digits, optionally separated by spaces or hyphens. A candidate counts only when it also passes the Luhn check.
     */
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    /** Not instantiable: this is a static utility. */
    private SecretGuard() {}

    /** Whether the text looks like it holds a secret. */
    public static boolean looksSecret(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        var matcher = CARD.matcher(text);
        while (matcher.find()) {
            if (luhn(matcher.group().replaceAll("[ -]", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Luhn checksum test for a string of digits. Returns false when the length is outside 13 to 19 digits,
     * which keeps ordinary long numbers such as phone numbers and identifiers from being refused.
     */
    private static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
