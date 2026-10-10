// @find: argument validator, validate tool arguments, json schema, normalise arguments, type coercion, missing field, invalid arguments, plain error message, model hallucinated field
// @what: Checks and tidies a tool call's arguments against the tool's own schema before anything is sent to a provider.
// @flow: Called by ToolGateway.evaluate
package os.aiworkforce.mcp.policy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import os.aiworkforce.mcp.model.ToolDefinition;

/**
 * Checks a model's tool arguments against the tool's own schema before anything is sent.
 *
 * <p>Models produce arguments that are close but wrong often enough that this is an ordinary
 * path: a missing required field, a string where a number belongs, an invented property. Catching
 * it here costs nothing; catching it at the provider costs a round trip, and for a tool with a
 * side effect it may cost a partly-completed action.
 *
 * <p>The message returned names the problem in terms the model can act on, because the model is
 * who reads it and gets one chance to correct itself.
 */
@Component
public class ArgumentValidator {

    private final ObjectMapper json;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /* Compiled schemas are cached: compiling one per invocation is pure waste on a hot path. */
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public ArgumentValidator(ObjectMapper json) {
        this.json = json;
    }

    // @find: normalise arguments, convert types, tidy tool input
    /**
     * The arguments with near misses put right, where the intent is not in doubt: {@code "10"}
     * for a whole number, {@code 41} for text, one address where a list is expected, {@code "true"}
     * for true or false, an object sent as a JSON string, and {@code null} for an optional field.
     *
     * <p>Models send these often - every one was seen from a live model in a real run - and a
     * call refused for {@code "limit":"10"} costs a turn, or ends the run, for no benefit to
     * anyone. Nothing is guessed: a value that does not convert cleanly is left as it was, and
     * {@link #validate} then names the field. Arguments that are not a JSON object are returned
     * unchanged.
     */
    public String normalise(ToolDefinition tool, String argumentsJson) {
        JsonNode arguments;
        JsonNode schema;
        try {
            arguments = json.readTree(argumentsJson == null ? "{}" : argumentsJson);
            schema = json.readTree(tool.parametersJson());
        } catch (Exception e) {
            return argumentsJson;
        }
        if (arguments == null || !arguments.isObject() || !schema.path("properties").isObject()) {
            return argumentsJson;
        }
        com.fasterxml.jackson.databind.node.ObjectNode fixed =
                ((com.fasterxml.jackson.databind.node.ObjectNode) arguments).deepCopy();
        Set<String> required = new java.util.HashSet<>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        boolean changed = false;
        var properties = schema.path("properties").fields();
        while (properties.hasNext()) {
            var property = properties.next();
            String field = property.getKey();
            if (!fixed.has(field)) {
                continue;
            }
            JsonNode value = fixed.get(field);
            if (value.isNull() && !required.contains(field)) {
                fixed.remove(field);
                changed = true;
                continue;
            }
            JsonNode converted = convert(value, property.getValue());
            if (converted != value) {
                fixed.set(field, converted);
                changed = true;
            }
        }
        return changed ? fixed.toString() : argumentsJson;
    }

    /* The value in the schema's type when it converts without doubt; otherwise the value itself. */
    private JsonNode convert(JsonNode value, JsonNode schema) {
        String type = schema.path("type").asText("");
        var nodes = json.getNodeFactory();
        switch (type) {
            case "integer" -> {
                if (value.isTextual() && value.asText().trim().matches("-?\\d{1,18}")) {
                    return nodes.numberNode(Long.parseLong(value.asText().trim()));
                }
                if (value.isNumber() && !value.isIntegralNumber() && value.asDouble() == Math.rint(value.asDouble())) {
                    return nodes.numberNode(value.asLong());
                }
            }
            case "number" -> {
                if (value.isTextual() && value.asText().trim().matches("-?\\d+(\\.\\d+)?")) {
                    return nodes.numberNode(new java.math.BigDecimal(value.asText().trim()));
                }
            }
            case "boolean" -> {
                String text = value.isTextual() ? value.asText().trim().toLowerCase(java.util.Locale.ROOT) : "";
                if ("true".equals(text) || "false".equals(text)) {
                    return nodes.booleanNode("true".equals(text));
                }
            }
            case "string" -> {
                if (value.isNumber() || value.isBoolean()) {
                    return nodes.textNode(value.asText());
                }
            }
            case "array" -> {
                JsonNode items = schema.path("items");
                if (value.isArray()) {
                    com.fasterxml.jackson.databind.node.ArrayNode converted = json.createArrayNode();
                    boolean any = false;
                    for (JsonNode item : value) {
                        JsonNode each = convert(item, items);
                        any |= each != item;
                        converted.add(each);
                    }
                    return any ? converted : value;
                }
                if (value.isValueNode() && !value.isNull()) {
                    com.fasterxml.jackson.databind.node.ArrayNode list = json.createArrayNode();
                    String text = value.asText();
                    // "a@x.com, b@y.com" is two addresses, not one with a comma in it.
                    String[] parts = text.split("\\s*[,;]\\s*");
                    boolean addresses = parts.length > 1
                            && java.util.Arrays.stream(parts).allMatch(part -> part.matches("[^@\\s]+@[^@\\s]+"));
                    if (addresses) {
                        java.util.Arrays.stream(parts).forEach(list::add);
                    } else {
                        list.add(convert(value, items));
                    }
                    return list;
                }
            }
            case "object" -> {
                if (value.isTextual() && value.asText().trim().startsWith("{")) {
                    try {
                        JsonNode parsed = json.readTree(value.asText());
                        if (parsed != null && parsed.isObject()) {
                            return parsed;
                        }
                    } catch (Exception e) {
                        return value;
                    }
                }
            }
            default -> {
                return value;
            }
        }
        return value;
    }

    // @find: validate tool arguments, schema check, returns problem text
    /** Returns null when the arguments are acceptable, or a sentence describing what is wrong. */
    public String validate(ToolDefinition tool, String argumentsJson) {
        JsonNode arguments;
        try {
            arguments = json.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception e) {
            // The commonest model failure: not valid JSON at all, often with prose wrapped round it.
            return "The arguments were not valid JSON. Send only a JSON object matching the tool's schema.";
        }
        if (!arguments.isObject()) {
            return "The arguments must be a JSON object.";
        }

        JsonSchema schema = compiled.computeIfAbsent(tool.qualifiedName(), key -> {
            try {
                return factory.getSchema(json.readTree(tool.parametersJson()));
            } catch (Exception e) {
                return null;
            }
        });
        if (schema == null) {
            // A tool whose own schema is unreadable is our defect. Blocking every call to it
            // would be worse than letting the provider judge the arguments.
            return null;
        }

        Set<ValidationMessage> problems = schema.validate(arguments);
        if (problems.isEmpty()) {
            return null;
        }
        String detail = problems.stream()
                .map(ValidationMessage::getMessage)
                .map(ArgumentValidator::plain)
                .distinct()
                .limit(5)
                .collect(Collectors.joining("; "));
        return "The arguments do not match the tool's schema: " + detail;
    }

    private static final java.util.regex.Pattern REQUIRED =
            java.util.regex.Pattern.compile("^\\$(?:\\.(\\S+))?: required property '([^']+)' not found$");
    private static final java.util.regex.Pattern WRONG_TYPE =
            java.util.regex.Pattern.compile("^\\$\\.(\\S+): (\\w+) found, (\\w+) expected$");
    private static final java.util.regex.Pattern AT_FIELD = java.util.regex.Pattern.compile("^\\$\\.(\\S+): (.*)$");

    /**
     * The validator's message in words a model and a person both read without knowing JSON Path:
     * {@code $: required property 'body' not found} becomes {@code body is required}, and
     * {@code $.limit: string found, integer expected} becomes {@code limit must be a whole number,
     * not text}. The field is always named, because that is what the reader has to fix.
     */
    static String plain(String message) {
        java.util.regex.Matcher required = REQUIRED.matcher(message);
        if (required.matches()) {
            String parent = required.group(1);
            return (parent == null ? "" : parent + ".") + required.group(2) + " is required";
        }
        java.util.regex.Matcher wrong = WRONG_TYPE.matcher(message);
        if (wrong.matches()) {
            return wrong.group(1) + " must be " + typeName(wrong.group(3)) + ", not " + typeName(wrong.group(2));
        }
        java.util.regex.Matcher at = AT_FIELD.matcher(message);
        if (at.matches()) {
            return at.group(1) + ": " + at.group(2);
        }
        return message.startsWith("$: ") ? message.substring(3) : message;
    }

    private static String typeName(String type) {
        return switch (type) {
            case "string" -> "text";
            case "integer" -> "a whole number";
            case "number" -> "a number";
            case "boolean" -> "true or false";
            case "array" -> "a list";
            case "object" -> "an object";
            case "null" -> "empty";
            default -> type;
        };
    }
}
