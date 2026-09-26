package os.aiworkforce.llm.model;

import java.util.Objects;

/**
 * A tool offered to the model.
 *
 * <p>Only tools the agent has actually been granted are ever built into a spec. A tool the agent
 * may not use is not described to the model at all, rather than being described and then refused:
 * a model that can see a capability will keep reaching for it, and every attempt costs a turn and
 * produces an error the person has to read.
 *
 * @param name the tool identifier, stable across providers
 * @param description what the tool does, written for the model rather than for a person
 * @param parametersJson JSON Schema for the arguments
 * @param sideEffect what running it does, which decides whether an approval gate applies
 */
public record ToolSpec(String name, String description, String parametersJson, SideEffect sideEffect) {

    public enum SideEffect {
        /** Reads data and changes nothing. */
        READ,
        /** Creates or changes something inside the workspace. */
        WRITE,
        /** Leaves the workspace: an email, a message, a post. Cannot be taken back. */
        OUTBOUND,
        /** Removes something. Always gated, whatever the policy says. */
        DESTRUCTIVE
    }

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        parametersJson = parametersJson == null ? "{\"type\":\"object\",\"properties\":{}}" : parametersJson;
        sideEffect = sideEffect == null ? SideEffect.READ : sideEffect;
    }

    public boolean requiresApprovalByNature() {
        return sideEffect == SideEffect.OUTBOUND || sideEffect == SideEffect.DESTRUCTIVE;
    }
}
