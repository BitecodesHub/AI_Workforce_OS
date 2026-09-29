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
                .distinct()
                .limit(5)
                .collect(Collectors.joining("; "));
        return "The arguments do not match the tool's schema: " + detail;
    }
}
