package os.aiworkforce.llm.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the platform asks a model to do, independent of which model answers.
 *
 * <p>The request states its <em>requirements</em> rather than naming a model. That inversion is
 * what makes fallover possible: the router can only tell whether a second candidate is an
 * acceptable substitute if it knows the call needs tool calling, or strict JSON, or a window of a
 * certain size. A request that named a model would leave the router guessing.
 *
 * @param messages the conversation so far
 * @param tools tools the agent is permitted to use on this call
 * @param requireToolSupport whether a candidate without tool calling is disqualified
 * @param jsonMode whether the answer must be valid JSON
 * @param jsonSchema optional schema the answer must satisfy
 * @param maxOutputTokens cap on the answer, never on the prompt
 * @param temperature sampling temperature, or null to accept the model's default
 * @param stopSequences sequences that end generation
 * @param timeout how long the caller is prepared to wait for one attempt
 * @param metadata correlation values recorded with the attempt, never sent to the provider
 * @param idempotencyKey makes a retried call safe to repeat without paying twice
 */
public record ChatRequest(
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        boolean requireToolSupport,
        boolean jsonMode,
        String jsonSchema,
        Integer maxOutputTokens,
        Double temperature,
        List<String> stopSequences,
        Duration timeout,
        Map<String, String> metadata,
        String idempotencyKey) {

    public ChatRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A chat request needs at least one message");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The system prompt, which several providers carry outside the message list. */
    public String systemPrompt() {
        return messages.stream()
                .filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content)
                .filter(Objects::nonNull)
                .reduce((first, second) -> first + "\n\n" + second)
                .orElse(null);
    }

    /** The conversation without system turns, for providers that take them separately. */
    public List<ChatMessage> conversation() {
        return messages.stream()
                .filter(m -> m.role() != ChatMessage.Role.SYSTEM)
                .toList();
    }

    public boolean usesTools() {
        return !tools.isEmpty();
    }

    public int estimatedPromptTokens() {
        return TokenEstimate.forRequest(this);
    }

    /** The same request with a different conversation, used when the transcript is compacted. */
    public ChatRequest withMessages(List<ChatMessage> replacement) {
        return new ChatRequest(
                replacement,
                tools,
                requireToolSupport,
                jsonMode,
                jsonSchema,
                maxOutputTokens,
                temperature,
                stopSequences,
                timeout,
                metadata,
                idempotencyKey);
    }

    /** The same request with tools removed, for a candidate that cannot call them. */
    public ChatRequest withoutTools() {
        return new ChatRequest(
                messages,
                List.of(),
                false,
                jsonMode,
                jsonSchema,
                maxOutputTokens,
                temperature,
                stopSequences,
                timeout,
                metadata,
                idempotencyKey);
    }

    public static final class Builder {
        private List<ChatMessage> messages = List.of();
        private List<ToolSpec> tools = List.of();
        private boolean requireToolSupport;
        private boolean jsonMode;
        private String jsonSchema;
        private Integer maxOutputTokens;
        private Double temperature;
        private List<String> stopSequences = List.of();
        private Duration timeout;
        private Map<String, String> metadata = Map.of();
        private String idempotencyKey;

        public Builder messages(List<ChatMessage> value) {
            this.messages = value;
            return this;
        }

        public Builder tools(List<ToolSpec> value) {
            this.tools = value;
            // Offering tools without requiring support would let the router fall over to a model
            // that silently ignores them, and the agent would appear to refuse its own job.
            this.requireToolSupport = value != null && !value.isEmpty();
            return this;
        }

        public Builder requireToolSupport(boolean value) {
            this.requireToolSupport = value;
            return this;
        }

        public Builder jsonMode(boolean value) {
            this.jsonMode = value;
            return this;
        }

        public Builder jsonSchema(String value) {
            this.jsonSchema = value;
            this.jsonMode = value != null;
            return this;
        }

        public Builder maxOutputTokens(Integer value) {
            this.maxOutputTokens = value;
            return this;
        }

        public Builder temperature(Double value) {
            this.temperature = value;
            return this;
        }

        public Builder stopSequences(List<String> value) {
            this.stopSequences = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder metadata(Map<String, String> value) {
            this.metadata = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(
                    messages,
                    tools,
                    requireToolSupport,
                    jsonMode,
                    jsonSchema,
                    maxOutputTokens,
                    temperature,
                    stopSequences,
                    timeout,
                    metadata,
                    idempotencyKey);
        }
    }
}
