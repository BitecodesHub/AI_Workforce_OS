package os.aiworkforce.platform.runtimeconfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A setting an operator can change while the platform is running.
 *
 * <p>The division from {@code PlatformProperties} is deliberate. A value that decides how the
 * process is wired - which database, which port, which signing key - belongs to startup, because
 * changing it mid-flight would leave half the process on the old value. A value that decides how
 * the platform <em>behaves</em> - how many retries, which model is the default, whether outbound
 * email needs approval - belongs here, because an operator needs it changed during an incident,
 * not after a deployment.
 *
 * <p>Each key declares its own type, its default, its bounds and whether a workspace may override
 * the platform's value. Declaring bounds is what makes the console safe: a text field that accepts
 * any number is how a retry budget becomes 10,000.
 *
 * @param name dotted key, for example {@code llm.router.max-attempts}
 * @param scope whether a workspace may override the platform value
 * @param type the value's shape, which the console renders and the writer validates
 * @param defaultValue the platform default when nothing is stored
 * @param description one sentence shown beside the field
 * @param minimum inclusive lower bound for numeric and duration keys
 * @param maximum inclusive upper bound for numeric and duration keys
 * @param allowedValues closed set for enumerated keys
 * @param sensitive whether the stored value must be encrypted and never read back in clear
 */
public record ConfigKey(
        String name,
        Scope scope,
        Type type,
        Object defaultValue,
        String description,
        Number minimum,
        Number maximum,
        List<String> allowedValues,
        boolean sensitive) {

    public enum Scope {
        /** One value for the whole deployment; only a platform operator may change it. */
        PLATFORM,
        /** A platform default that any workspace may override for itself. */
        WORKSPACE
    }

    public enum Type {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        DURATION,
        STRING,
        ENUM,
        STRING_LIST,
        JSON
    }

    public ConfigKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    // ---- Builders, so a declaration reads as a sentence ---------------------------------

    public static ConfigKey flag(String name, Scope scope, boolean defaultValue, String description) {
        return new ConfigKey(name, scope, Type.BOOLEAN, defaultValue, description, null, null, null, false);
    }

    public static ConfigKey integer(String name, Scope scope, int defaultValue, int min, int max, String description) {
        return new ConfigKey(name, scope, Type.INTEGER, defaultValue, description, min, max, null, false);
    }

    public static ConfigKey decimal(
            String name, Scope scope, double defaultValue, double min, double max, String description) {
        return new ConfigKey(name, scope, Type.DECIMAL, defaultValue, description, min, max, null, false);
    }

    public static ConfigKey duration(
            String name, Scope scope, Duration defaultValue, Duration min, Duration max, String description) {
        return new ConfigKey(
                name,
                scope,
                Type.DURATION,
                defaultValue.toString(),
                description,
                min == null ? null : min.toMillis(),
                max == null ? null : max.toMillis(),
                null,
                false);
    }

    public static ConfigKey text(String name, Scope scope, String defaultValue, String description) {
        return new ConfigKey(name, scope, Type.STRING, defaultValue, description, null, null, null, false);
    }

    public static ConfigKey choice(
            String name, Scope scope, String defaultValue, List<String> allowed, String description) {
        if (!allowed.contains(defaultValue)) {
            throw new IllegalArgumentException("Default " + defaultValue + " is not among the allowed values");
        }
        return new ConfigKey(name, scope, Type.ENUM, defaultValue, description, null, null, allowed, false);
    }

    public static ConfigKey list(String name, Scope scope, List<String> defaultValue, String description) {
        return new ConfigKey(name, scope, Type.STRING_LIST, defaultValue, description, null, null, null, false);
    }

    public static ConfigKey json(String name, Scope scope, JsonNode defaultValue, String description) {
        return new ConfigKey(name, scope, Type.JSON, defaultValue, description, null, null, null, false);
    }

    /** Marks the value as a secret: stored encrypted, never returned in clear. */
    public ConfigKey asSensitive() {
        return new ConfigKey(name, scope, type, defaultValue, description, minimum, maximum, allowedValues, true);
    }

    public boolean workspaceOverridable() {
        return scope == Scope.WORKSPACE;
    }

    /** JSON Schema for this key, used by the console to render a field and by the writer to validate. */
    public Map<String, Object> jsonSchema() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("title", name);
        schema.put("description", description);
        switch (type) {
            case BOOLEAN -> schema.put("type", "boolean");
            case INTEGER -> {
                schema.put("type", "integer");
                if (minimum != null) {
                    schema.put("minimum", minimum);
                }
                if (maximum != null) {
                    schema.put("maximum", maximum);
                }
            }
            case DECIMAL -> {
                schema.put("type", "number");
                if (minimum != null) {
                    schema.put("minimum", minimum);
                }
                if (maximum != null) {
                    schema.put("maximum", maximum);
                }
            }
            case DURATION -> {
                schema.put("type", "string");
                schema.put("pattern", "^P(T.*)?$");
                schema.put("format", "duration");
            }
            case ENUM -> {
                schema.put("type", "string");
                schema.put("enum", allowedValues);
            }
            case STRING_LIST -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string"));
            }
            case JSON -> schema.put("type", "object");
            case STRING -> schema.put("type", "string");
        }
        return Map.copyOf(schema);
    }

    /** Index of every key a module registers, so the console can list what is changeable. */
    public static Map<String, ConfigKey> index(List<ConfigKey> keys) {
        return keys.stream().collect(Collectors.toUnmodifiableMap(ConfigKey::name, Function.identity()));
    }
}
