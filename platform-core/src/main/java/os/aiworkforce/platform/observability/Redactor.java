package os.aiworkforce.platform.observability;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Removes secrets and personal data before anything is logged, traced or stored.
 *
 * <p>This platform captures a great deal on purpose: prompts, tool arguments, run traces, request
 * bodies. That is what makes an audit trail useful, and it is also how an API key ends up in a log
 * aggregator that a wider group can read than the database ever was.
 *
 * <p>Two passes, because neither alone is enough. Matching on the key catches
 * {@code {"api_key": "..."}}, where the name tells us what the value is. Matching on the value
 * catches a bearer token pasted into a free-text prompt, where no key names it at all.
 */
@Component
public class Redactor {

    public static final String MASK = "[redacted]";
    private static final int MAX_DEPTH = 12;

    /*
     * Value patterns, ordered from most to least specific. Each is anchored on a distinctive
     * prefix or shape rather than on "looks random", because a rule that vague would redact
     * legitimate content - document identifiers, hashes, base64 attachments - and make the trace
     * useless for the debugging it exists to support.
     */
    private static final List<Pattern> VALUE_PATTERNS = List.of(
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"), // OpenAI style
            Pattern.compile("\\bsk-or-v1-[A-Za-z0-9]{16,}"), // OpenRouter
            Pattern.compile("\\bgsk_[A-Za-z0-9]{16,}"), // Groq
            Pattern.compile("\\bnvapi-[A-Za-z0-9_-]{16,}"), // NVIDIA
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{30,}"), // Google
            Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}"), // Anthropic
            Pattern.compile("\\b(AKIA|ASIA)[A-Z0-9]{16}\\b"), // AWS access key id
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), // Slack
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), // GitHub
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), // JWT
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private final Set<String> sensitiveKeys;
    private final boolean redactEmails;

    public Redactor(PlatformProperties properties) {
        PlatformProperties.Observability observability = properties.observability();
        this.sensitiveKeys = observability.redactKeys().stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.redactEmails = observability.redactEmailAddresses();
    }

    /** Redacts free text, leaving everything that is not a recognised secret intact. */
    public String text(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        for (Pattern pattern : VALUE_PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        if (redactEmails) {
            // Kept partially readable: support needs to tell two addresses apart in a trace
            // without the trace being a mailing list.
            result = EMAIL.matcher(result).replaceAll(this::maskEmail);
        }
        return result;
    }

    private String maskEmail(java.util.regex.MatchResult match) {
        String address = match.group();
        int at = address.indexOf('@');
        String local = address.substring(0, at);
        String domain = address.substring(at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***" + domain;
    }

    /** Redacts a structured payload by key and by value, to any reasonable depth. */
    public JsonNode json(JsonNode node) {
        return redactNode(node, 0);
    }

    private JsonNode redactNode(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (depth > MAX_DEPTH) {
            // A payload nested deeper than this is either a mistake or an attempt to hide
            // something below the redactor. Truncating is the safe answer to both.
            return com.fasterxml.jackson.databind.node.TextNode.valueOf("[truncated: too deep]");
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).objectNode();
            node.fields().forEachRemaining(entry -> {
                if (isSensitiveKey(entry.getKey())) {
                    copy.put(entry.getKey(), MASK);
                } else {
                    copy.set(entry.getKey(), redactNode(entry.getValue(), depth + 1));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).arrayNode();
            node.forEach(child -> copy.add(redactNode(child, depth + 1)));
            return copy;
        }
        if (node.isTextual()) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(text(node.asText()));
        }
        return node;
    }

    /** Redacts a flat map, used for HTTP headers and metric tags. */
    public Map<String, String> map(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> isSensitiveKey(entry.getKey()) ? MASK : text(entry.getValue())));
    }

    /**
     * Whether a key names a secret.
     *
     * <p>Substring matching rather than equality, so {@code x-provider-api-key} and
     * {@code refreshTokenHash} are both caught. Over-matching here costs a little readability;
     * under-matching costs a credential.
     */
    public boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalised = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return sensitiveKeys.stream().anyMatch(sensitive -> normalised.contains(sensitive.replace('-', '_')));
    }

    /** Masks a value while keeping its shape visible, for a fingerprint in the interface. */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return MASK;
        }
        if (value.length() <= 8) {
            return MASK;
        }
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }

    static String replaceAll(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll(MASK);
    }
}
