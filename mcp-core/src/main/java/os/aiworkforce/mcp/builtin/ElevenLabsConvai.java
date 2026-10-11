// @find: ElevenLabs conversational AI client, convai agents, list voice agents, create voice agent, update voice agent, phone numbers in ElevenLabs, import Twilio number into ElevenLabs, assign agent to number, outbound call, conversations, ElevenLabsConvai
// @what: The ElevenLabs Agents Platform calls the phone and Twilio connectors share: agents, phone numbers, outbound calls and conversations, each with the workspace's key in xi-api-key.
// @flow: Used by PhoneAdapter (phone__ tools) and TwilioAdapter (connect_number_to_elevenlabs, list_numbers); calls api.elevenlabs.io
package os.aiworkforce.mcp.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * A thin client for the ElevenLabs Agents Platform ("convai") endpoints the telephone tools use.
 * It knows the request shapes and nothing about tools, approvals or results: the adapters turn
 * what it answers into plain results. The key is passed into every call and never kept.
 */
public final class ElevenLabsConvai {

    /** Production endpoint; a different base URL may be passed to the constructor, for tests. */
    public static final String BASE_URL = "https://api.elevenlabs.io";
    /** Header that carries the workspace key on every request. */
    static final String KEY_HEADER = "xi-api-key";

    /** The workspace's own caller, made the first time a call needs one. */
    public static final String CALLER_NAME = "AI Workforce caller";

    /** Builds request bodies. */
    private final ObjectMapper json;
    private final WebClient http;

    /**
     * Builds the client. A null or blank base URL means the production endpoint. The key is
     * not stored here; every method takes it as an argument.
     */
    public ElevenLabsConvai(ObjectMapper json, WebClient.Builder http, String baseUrl) {
        this.json = json;
        this.http = http.clone().baseUrl(baseUrl == null || baseUrl.isBlank() ? BASE_URL : baseUrl).build();
    }

    /** One agent, as listed. */
    public record Agent(String id, String name) {}

    // ---- Phone numbers ------------------------------------------------------------------------

    // @find: list ElevenLabs phone numbers
    /**
     * The phone numbers on the ElevenLabs account, in its own order. Read-only HTTP call; a non-array
     * reply gives an empty list and an HTTP error status surfaces as an error in the {@code Mono}.
     */
    public Mono<List<JsonNode>> phoneNumbers(String key) {
        return http.get()
                .uri("/v1/convai/phone-numbers")
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(numbers -> {
                    List<JsonNode> all = new ArrayList<>();
                    if (numbers != null && numbers.isArray()) {
                        numbers.forEach(all::add);
                    }
                    return all;
                });
    }

    /**
     * A number that can make calls: the one asked for when given, otherwise the first. A match is by
     * phone number or by ElevenLabs id. Numbers that cannot place calls are skipped. Pure.
     */
    public static Optional<JsonNode> outboundNumber(List<JsonNode> numbers, String wanted) {
        String normalised = wanted == null ? "" : PhoneNumbers.normalise(wanted);
        for (JsonNode number : numbers) {
            if (!number.path("supports_outbound").asBoolean(true) || !number.hasNonNull("phone_number_id")) {
                continue;
            }
            if (normalised.isEmpty()
                    || normalised.equals(PhoneNumbers.normalise(number.path("phone_number").asText("")))
                    || normalised.equals(number.path("phone_number_id").asText())) {
                return Optional.of(number);
            }
        }
        return Optional.empty();
    }

    // @find: import Twilio number into ElevenLabs
    /**
     * Imports a Twilio number; answers its new ElevenLabs phone_number_id. A write to the account that
     * hands the Twilio account SID and token to ElevenLabs, so the Twilio connector asks a person first.
     * HTTP errors surface in the {@code Mono}; the credentials are never logged here.
     */
    public Mono<String> importTwilioNumber(String key, String phoneNumber, String label, String accountSid, String authToken) {
        ObjectNode body = json.createObjectNode();
        body.put("phone_number", phoneNumber);
        body.put("label", label);
        body.put("sid", accountSid);
        body.put("token", authToken);
        body.put("provider", "twilio");
        return http.post()
                .uri("/v1/convai/phone-numbers")
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(answer -> answer.path("phone_number_id").asText(""));
    }

    // @find: assign ElevenLabs agent to phone number
    /**
     * Points a phone number at an agent so inbound calls reach it. A write to the ElevenLabs account.
     * Called by the Twilio connector after an import. An HTTP error status surfaces as an error in the
     * returned {@code Mono}; an empty reply becomes an empty object.
     */
    public Mono<JsonNode> assignAgent(String key, String phoneNumberId, String agentId) {
        return http.patch()
                .uri("/v1/convai/phone-numbers/{id}", phoneNumberId)
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json.createObjectNode().put("agent_id", agentId))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    // ---- Agents -------------------------------------------------------------------------------

    // @find: list ElevenLabs voice agents
    /**
     * Lists up to 100 agents, optionally filtered by a search text. Read-only call to the agents
     * endpoint. Entries without an agent id are skipped. HTTP errors surface in the {@code Mono}.
     */
    public Mono<List<Agent>> agents(String key, String search) {
        return http.get()
                .uri(uri -> {
                    uri.path("/v1/convai/agents").queryParam("page_size", 100);
                    if (search != null && !search.isBlank()) {
                        uri.queryParam("search", search);
                    }
                    return uri.build();
                })
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(answer -> {
                    List<Agent> agents = new ArrayList<>();
                    for (JsonNode agent : answer.path("agents")) {
                        if (agent.hasNonNull("agent_id")) {
                            agents.add(new Agent(agent.path("agent_id").asText(), agent.path("name").asText("")));
                        }
                    }
                    return agents;
                });
    }

    /** The agent named or identified by {@code nameOrId}: an exact id first, then a name ignoring case. */
    public Mono<Optional<Agent>> findAgent(String key, String nameOrId) {
        String wanted = nameOrId == null ? "" : nameOrId.strip();
        if (wanted.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return agents(key, null).map(agents -> {
            for (Agent agent : agents) {
                if (agent.id().equals(wanted)) {
                    return Optional.of(agent);
                }
            }
            String lower = wanted.toLowerCase(Locale.ROOT);
            for (Agent agent : agents) {
                if (agent.name().toLowerCase(Locale.ROOT).equals(lower)) {
                    return Optional.of(agent);
                }
            }
            return Optional.<Agent>empty();
        });
    }

    // @find: create ElevenLabs voice agent
    /**
     * Makes an agent that lets each call set its own prompt and first message, so one agent can
     * serve many purposes without being edited between calls. Answers the new agent_id.
     * A write to the account (the agent is created once and reused); HTTP errors surface in the
     * {@code Mono}. Callers treat creating an agent as something a person approves.
     */
    public Mono<String> createAgent(
            String key, String name, String prompt, String firstMessage, String voiceId, String language) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        ObjectNode config = body.putObject("conversation_config");
        ObjectNode agent = config.putObject("agent");
        agent.put("first_message", firstMessage == null ? "" : firstMessage);
        agent.put("language", language == null || language.isBlank() ? "en" : language);
        agent.putObject("prompt").put("prompt", prompt);
        if (voiceId != null && !voiceId.isBlank()) {
            config.putObject("tts").put("voice_id", voiceId);
        }
        ObjectNode override = body.putObject("platform_settings")
                .putObject("overrides")
                .putObject("conversation_config_override")
                .putObject("agent");
        override.put("first_message", true);
        override.putObject("prompt").put("prompt", true);
        return http.post()
                .uri("/v1/convai/agents/create")
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(created -> created.path("agent_id").asText(""));
    }

    // @find: update ElevenLabs voice agent
    /**
     * Changes only what is given; null leaves a setting as it is. A write to the account that changes
     * how every later call with this agent behaves. HTTP errors surface in the {@code Mono}.
     */
    public Mono<JsonNode> updateAgent(
            String key, String agentId, String name, String prompt, String firstMessage, String voiceId, String language) {
        ObjectNode body = json.createObjectNode();
        if (name != null && !name.isBlank()) {
            body.put("name", name);
        }
        ObjectNode config = json.createObjectNode();
        ObjectNode agent = json.createObjectNode();
        if (firstMessage != null) {
            agent.put("first_message", firstMessage);
        }
        if (language != null && !language.isBlank()) {
            agent.put("language", language);
        }
        if (prompt != null && !prompt.isBlank()) {
            agent.putObject("prompt").put("prompt", prompt);
        }
        if (!agent.isEmpty()) {
            config.set("agent", agent);
        }
        if (voiceId != null && !voiceId.isBlank()) {
            config.putObject("tts").put("voice_id", voiceId);
        }
        if (!config.isEmpty()) {
            body.set("conversation_config", config);
        }
        return http.patch()
                .uri("/v1/convai/agents/{id}", agentId)
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    // ---- Calls --------------------------------------------------------------------------------

    // @find: ElevenLabs outbound call, twilio outbound call, sip trunk outbound call
    /**
     * Starts an outbound call from {@code number} with {@code agentId}. The endpoint follows the
     * number's provider: Twilio numbers through the Twilio endpoint, SIP trunk numbers through the
     * SIP one. Prompt and first message are per-call overrides and are left out when null.
     *
     * <p>Outbound side effect: this call rings a real telephone and may cost money, so the phone
     * connector always asks a person first and checks the number with {@link PhoneNumbers} before
     * reaching here. It is not retried. HTTP errors surface in the {@code Mono}.
     */
    public Mono<JsonNode> outboundCall(
            String key, String agentId, JsonNode number, String to, String prompt, String firstMessage) {
        boolean sip = "sip_trunk".equals(number.path("provider").asText());
        ObjectNode body = json.createObjectNode();
        body.put("agent_id", agentId);
        body.put("agent_phone_number_id", number.path("phone_number_id").asText());
        body.put("to_number", to);
        if (prompt != null || firstMessage != null) {
            ObjectNode agent = body.putObject("conversation_initiation_client_data")
                    .putObject("conversation_config_override")
                    .putObject("agent");
            if (firstMessage != null) {
                agent.put("first_message", firstMessage);
            }
            if (prompt != null) {
                agent.putObject("prompt").put("prompt", prompt);
            }
        }
        return http.post()
                .uri(sip ? "/v1/convai/sip-trunk/outbound-call" : "/v1/convai/twilio/outbound-call")
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    // @find: ElevenLabs conversation details, call transcript
    /**
     * One conversation with its transcript and status, by id. Read-only; used to report how a call
     * went. HTTP errors surface in the {@code Mono}.
     */
    public Mono<JsonNode> conversation(String key, String id) {
        return http.get()
                .uri("/v1/convai/conversations/{id}", id)
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    // @find: list ElevenLabs conversations
    /**
     * Recent conversations, newest first as ElevenLabs orders them, up to {@code limit}, optionally
     * for one agent. Read-only. HTTP errors surface in the {@code Mono}.
     */
    public Mono<JsonNode> conversations(String key, String agentId, int limit) {
        return http.get()
                .uri(uri -> {
                    uri.path("/v1/convai/conversations").queryParam("page_size", limit);
                    if (agentId != null) {
                        uri.queryParam("agent_id", agentId);
                    }
                    return uri.build();
                })
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }
}
