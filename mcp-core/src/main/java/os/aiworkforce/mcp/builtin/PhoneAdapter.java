// @find: phone calls connector, place call, outbound call, ElevenLabs conversational AI, convai, voice agents, list voice agents, create voice agent, update voice agent, list phone numbers, get call, list calls, call transcript, call summary, no phone number connected, caller agent, PhoneAdapter
// @what: Built-in phone calls connector: manages the workspace's ElevenLabs voice agents and imported numbers, places outbound calls and reads back their status, length, transcript and summary.
// @flow: Registered by SandboxServerRegistry for the phone server; invoked through ToolGateway.invoke; reads the ElevenLabs key through WorkspaceSecrets; calls ElevenLabsConvai
package os.aiworkforce.mcp.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.mcp.spi.WorkspaceSecrets;

/**
 * Phone calls through the workspace's own ElevenLabs account, with the key stored for voice.
 *
 * <p>A call needs a phone number imported into ElevenLabs (from Twilio, which the Twilio connector
 * can do, or over a SIP trunk) and an ElevenLabs voice agent to speak. The agent is the one named
 * in the call, or the workspace's own {@value ElevenLabsConvai#CALLER_NAME}, made the first time
 * it is needed; a call's purpose and opening line are per-call overrides, so nothing about one call
 * stays on the agent for the next.
 *
 * <p>Governance: {@code place_call} is OUTBOUND, so a person approves every call. Making or changing
 * a voice agent is a WRITE, but it changes what is said to people on the phone, so it asks too
 * ({@link #approvalReason}). The number is checked before anything is sent: E.164 only and never a
 * premium-rate range. The phone number is looked up before any agent is found or made, so an
 * account with no number is told so without anything being created on it.
 */
public final class PhoneAdapter implements McpServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PhoneAdapter.class);

    public static final String BASE_URL = ElevenLabsConvai.BASE_URL;
    static final String CALLER_NAME = ElevenLabsConvai.CALLER_NAME;

    public static final String NO_NUMBER = "No phone number is connected in ElevenLabs, so no call can be placed."
            + " Connect Twilio in Connectors and ask an agent to connect a number, or add one in ElevenLabs under"
            + " Phone numbers, then try again.";
    public static final String NO_KEY = "No ElevenLabs key is stored for this workspace, so nothing was done."
            + " An administrator can add it on the Connectors page, under ElevenLabs voice.";
    static final String STORE_DOWN = "Could not reach the key store, so nothing was done. Try again shortly.";
    static final String KEY_REFUSED =
            "ElevenLabs did not accept the stored key. An administrator can store a new one on the Connectors page.";

    static final String DEFAULT_FIRST_MESSAGE =
            "Hello, this is an automated assistant calling. Is now a good time to talk for a minute?";

    /* Writes that change what callers hear: a person approves each one, whatever the grant says. */
    private static final Set<String> ASKS_FIRST = Set.of("create_voice_agent", "update_voice_agent");
    private static final int TRANSCRIPT_TURNS = 40;

    private final List<ToolDefinition> tools;
    private final ObjectMapper json;
    private final ElevenLabsConvai convai;
    private final Supplier<WorkspaceSecrets> secrets;
    /* The caller agent's id per workspace, so it is looked up once and not on every call. */
    private final Map<String, String> callerAgents = new ConcurrentHashMap<>();

    public PhoneAdapter(
            List<ToolDefinition> tools,
            ObjectMapper json,
            WebClient.Builder http,
            String baseUrl,
            Supplier<WorkspaceSecrets> secrets) {
        this.tools = List.copyOf(tools);
        this.json = json;
        this.convai = new ElevenLabsConvai(json, http, baseUrl);
        this.secrets = secrets;
    }

    @Override
    public String server() {
        return "phone";
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public boolean isBuiltIn() {
        return true;
    }

    @Override
    public Mono<Boolean> healthCheck(String credential) {
        return Mono.just(true);
    }

    @Override
    public boolean asksEveryCall(String tool) {
        return ASKS_FIRST.contains(tool);
    }

    // @find: voice agent changes ask first, approval for create or update voice agent
    @Override
    public Optional<String> approvalReason(ToolInvocation invocation) {
        if (!ASKS_FIRST.contains(invocation.tool())) {
            return Optional.empty();
        }
        String name = "";
        try {
            JsonNode arguments = json.readTree(invocation.argumentsJson());
            name = arguments.path("create_voice_agent".equals(invocation.tool()) ? "name" : "agent").asText("");
        } catch (Exception ignored) {
            // The gateway has already validated the arguments; a name is only for the sentence.
        }
        String what = "create_voice_agent".equals(invocation.tool()) ? "Create the ElevenLabs voice agent"
                : "Change the ElevenLabs voice agent";
        return Optional.of(what + (name.isBlank() ? "" : " \"" + name + "\"")
                + ", which speaks to people on phone calls, using phone." + invocation.tool());
    }

    // @find: run phone tool, place call, get call, list calls, voice agents
    @Override
    public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
        Instant startedAt = Instant.now();
        JsonNode arguments;
        try {
            arguments = json.readTree(invocation.argumentsJson());
        } catch (Exception e) {
            return Mono.just(ToolResult.failed("The arguments were not valid JSON."));
        }
        return Mono.fromCallable(() -> keyOf(invocation.orgId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(key -> switch (invocation.tool()) {
                    case "place_call" -> placeCall(invocation, arguments, key);
                    case "get_call" -> getCall(arguments, key);
                    case "list_calls" -> listCalls(invocation, arguments, key);
                    case "list_voice_agents" -> listAgents(key);
                    case "create_voice_agent" -> createAgent(arguments, key);
                    case "update_voice_agent" -> updateAgent(arguments, key);
                    case "list_phone_numbers" -> listPhoneNumbers(key);
                    default -> Mono.just(ToolResult.failed("Phone calls has no tool named " + invocation.tool() + "."));
                })
                .onErrorResume(Plain.class, plain -> Mono.just(ToolResult.failed(plain.getMessage())))
                .onErrorResume(WebClientResponseException.class, refused -> Mono.just(vendorRefusal(refused)))
                .onErrorResume(WebClientRequestException.class, down -> Mono.just(ToolResult.failed(
                        "ElevenLabs could not be reached, so nothing was done. Try again shortly.")))
                .map(result -> new ToolResult(
                        result.status(),
                        result.contentJson(),
                        result.summary(),
                        Duration.between(startedAt, Instant.now()),
                        result.metadata()));
    }

    /** A refusal that is already one plain sentence: no key, no number, an unknown agent. */
    static final class Plain extends RuntimeException {
        Plain(String message) {
            super(message, null, false, false);
        }
    }

    private String keyOf(String orgId) {
        WorkspaceSecrets source = secrets == null ? null : secrets.get();
        if (source == null) {
            throw new Plain(NO_KEY);
        }
        Optional<String> key;
        try {
            key = source.elevenLabsKey(orgId);
        } catch (RuntimeException e) {
            throw new Plain(STORE_DOWN);
        }
        return key.filter(value -> !value.isBlank()).map(String::strip).orElseThrow(() -> new Plain(NO_KEY));
    }

    // ---- place_call ---------------------------------------------------------------------------

    // @find: place outbound phone call
    private Mono<ToolResult> placeCall(ToolInvocation invocation, JsonNode arguments, String key) {
        String to = PhoneNumbers.normalise(arguments.path("to_number").asText(""));
        String problem = PhoneNumbers.problem(to);
        if (problem != null) {
            return Mono.just(ToolResult.failed(problem));
        }
        String agentAsked = text(arguments, "agent");
        String purpose = text(arguments, "purpose");
        String firstMessage = text(arguments, "first_message");
        if (agentAsked == null && purpose == null) {
            return Mono.just(ToolResult.failed(
                    "Say what the call is for in purpose, or name a voice agent that already knows, so the caller"
                            + " knows what to ask."));
        }
        String fromAsked = text(arguments, "from_number");
        return convai.phoneNumbers(key).flatMap(numbers -> {
            if (numbers.isEmpty()) {
                return Mono.just(ToolResult.failed(NO_NUMBER));
            }
            Optional<JsonNode> number = ElevenLabsConvai.outboundNumber(numbers, fromAsked);
            if (number.isEmpty()) {
                return Mono.just(ToolResult.failed(fromAsked == null
                        ? NO_NUMBER
                        : "No number " + fromAsked + " that can make calls is connected in ElevenLabs. Use"
                                + " phone.list_phone_numbers to see the ones that are."));
            }
            JsonNode chosen = number.get();
            Mono<String> agentId = agentAsked == null
                    ? callerAgent(invocation.orgId(), key)
                    : convai.findAgent(key, agentAsked).map(found -> found.map(ElevenLabsConvai.Agent::id)
                            .orElseThrow(() -> new Plain("No ElevenLabs voice agent is called \"" + agentAsked
                                    + "\". Use phone.list_voice_agents to see the ones that exist.")));
            // The workspace caller always gets the purpose and a first line; a named agent keeps its
            // own unless the call gives them.
            String prompt = purpose == null ? null : promptFor(purpose);
            String opening = firstMessage != null ? firstMessage : agentAsked == null ? DEFAULT_FIRST_MESSAGE : null;
            return agentId.flatMap(id -> convai.outboundCall(key, id, chosen, to, prompt, opening)).map(answer -> {
                if (!answer.path("success").asBoolean(false)) {
                    String why = answer.path("message").asText("");
                    return ToolResult.failed(
                            "ElevenLabs did not start the call" + (why.isBlank() ? "." : ": " + trim(why, 200)));
                }
                ObjectNode content = json.createObjectNode();
                content.put("call_id", answer.path("conversation_id").asText(""));
                content.put("status", "initiated");
                content.put("to_number", to);
                content.put("from_number", chosen.path("phone_number").asText(""));
                content.put("note", "The call has started. Use phone.get_call with this call_id later for its"
                        + " status, length and summary.");
                return ToolResult.succeeded(
                        content.toString(),
                        "Started a call to " + to + " from " + chosen.path("phone_number").asText("") + ".",
                        Duration.ZERO);
            });
        });
    }

    /** The workspace's caller agent, found by name or made once. */
    private Mono<String> callerAgent(String orgId, String key) {
        String known = callerAgents.get(orgId);
        if (known != null) {
            return Mono.just(known);
        }
        return convai.agents(key, CALLER_NAME)
                .flatMap(agents -> agents.stream()
                        .filter(agent -> CALLER_NAME.equals(agent.name()))
                        .findFirst()
                        .map(agent -> Mono.just(agent.id()))
                        .orElseGet(() -> convai.createAgent(key, CALLER_NAME, promptFor("Each call states its own purpose."),
                                        DEFAULT_FIRST_MESSAGE, null, "en")
                                .filter(id -> !id.isBlank())
                                .switchIfEmpty(Mono.error(new Plain(
                                        "ElevenLabs did not create the caller agent, so no call was placed.")))
                                .doOnNext(id -> log.info("Created the ElevenLabs caller agent {}", id))))
                .doOnNext(id -> callerAgents.put(orgId, id));
    }

    static String promptFor(String purpose) {
        return "You are an AI assistant making a short phone call on behalf of a team. Say early in the call that you"
                + " are an automated assistant. Be polite and brief, stay on the purpose below, and do not make"
                + " commitments, payments or promises. If the person asks you to stop or not to call again, apologise"
                + " and end the call. End the call once the purpose is done.\n\nPurpose of this call: " + purpose;
    }

    // ---- Agents and numbers -------------------------------------------------------------------

    // @find: list voice agents tool
    private Mono<ToolResult> listAgents(String key) {
        return convai.agents(key, null).map(agents -> {
            ArrayNode items = json.createArrayNode();
            agents.forEach(agent -> items.addObject().put("agent_id", agent.id()).put("name", agent.name()));
            return ToolResult.succeeded(
                    list(items).toString(), "Listed " + items.size() + " ElevenLabs voice agent(s).", Duration.ZERO);
        });
    }

    // @find: create voice agent tool
    private Mono<ToolResult> createAgent(JsonNode arguments, String key) {
        String name = text(arguments, "name");
        String prompt = text(arguments, "prompt");
        if (name == null || prompt == null) {
            return Mono.just(ToolResult.failed("Give the voice agent a name and a prompt."));
        }
        return convai.findAgent(key, name).flatMap(existing -> {
            if (existing.isPresent()) {
                return Mono.just(ToolResult.failed("A voice agent called \"" + name + "\" already exists. Use"
                        + " phone.update_voice_agent to change it."));
            }
            return convai.createAgent(key, name, prompt, text(arguments, "first_message"), text(arguments, "voice_id"),
                            text(arguments, "language"))
                    .map(id -> {
                        if (id.isBlank()) {
                            return ToolResult.failed("ElevenLabs did not create the voice agent.");
                        }
                        ObjectNode content = json.createObjectNode().put("agent_id", id).put("name", name);
                        return ToolResult.succeeded(
                                content.toString(), "Created the ElevenLabs voice agent \"" + name + "\".", Duration.ZERO);
                    });
        });
    }

    // @find: update voice agent tool
    private Mono<ToolResult> updateAgent(JsonNode arguments, String key) {
        String wanted = text(arguments, "agent");
        if (wanted == null) {
            return Mono.just(ToolResult.failed("Name the voice agent to change, by its name or agent_id."));
        }
        String name = text(arguments, "name");
        String prompt = text(arguments, "prompt");
        String first = text(arguments, "first_message");
        String voice = text(arguments, "voice_id");
        String language = text(arguments, "language");
        if (name == null && prompt == null && first == null && voice == null && language == null) {
            return Mono.just(ToolResult.failed("Say what to change: name, prompt, first_message, voice_id or language."));
        }
        return convai.findAgent(key, wanted).flatMap(found -> {
            if (found.isEmpty()) {
                return Mono.just(ToolResult.failed("No ElevenLabs voice agent is called \"" + wanted
                        + "\". Use phone.list_voice_agents to see the ones that exist."));
            }
            ElevenLabsConvai.Agent agent = found.get();
            return convai.updateAgent(key, agent.id(), name, prompt, first, voice, language).map(answer -> {
                ObjectNode content = json.createObjectNode()
                        .put("agent_id", agent.id())
                        .put("name", name == null ? agent.name() : name);
                return ToolResult.succeeded(content.toString(),
                        "Updated the ElevenLabs voice agent \"" + agent.name() + "\".", Duration.ZERO);
            });
        });
    }

    // @find: list phone numbers in ElevenLabs tool
    private Mono<ToolResult> listPhoneNumbers(String key) {
        return convai.phoneNumbers(key).map(numbers -> {
            ArrayNode items = json.createArrayNode();
            for (JsonNode number : numbers) {
                ObjectNode item = items.addObject()
                        .put("phone_number_id", number.path("phone_number_id").asText(""))
                        .put("phone_number", number.path("phone_number").asText(""))
                        .put("label", number.path("label").asText(""))
                        .put("provider", number.path("provider").asText(""))
                        .put("can_call_out", number.path("supports_outbound").asBoolean(true));
                JsonNode assigned = number.path("assigned_agent");
                item.put("assigned_agent", assigned.isObject() ? assigned.path("agent_name").asText("") : "");
                item.put("assigned_agent_id", assigned.isObject() ? assigned.path("agent_id").asText("") : "");
            }
            String summary = items.isEmpty()
                    ? "No phone numbers are connected in ElevenLabs yet."
                    : "Listed " + items.size() + " phone number(s) connected in ElevenLabs.";
            return ToolResult.succeeded(list(items).toString(), summary, Duration.ZERO);
        });
    }

    // ---- get_call and list_calls ------------------------------------------------------------------

    // @find: get call status, call transcript, call summary
    private Mono<ToolResult> getCall(JsonNode arguments, String key) {
        String id = arguments.path("call_id").asText("").strip();
        if (!id.matches("[A-Za-z0-9_-]{4,128}")) {
            return Mono.just(ToolResult.failed("Give the call_id that place_call returned."));
        }
        return convai.conversation(key, id).map(conversation -> {
            ObjectNode content = json.createObjectNode();
            content.put("call_id", id);
            content.put("status", conversation.path("status").asText("unknown"));
            content.put("duration_secs", conversation.path("metadata").path("call_duration_secs").asInt(0));
            content.put("summary", conversation.path("analysis").path("transcript_summary").asText(""));
            if (conversation.path("analysis").hasNonNull("call_successful")) {
                content.put("call_successful", conversation.path("analysis").path("call_successful").asText());
            }
            ArrayNode transcript = content.putArray("transcript");
            int turns = 0;
            for (JsonNode turn : conversation.path("transcript")) {
                if (turns++ >= TRANSCRIPT_TURNS) {
                    break;
                }
                String message = turn.path("message").asText("");
                if (message.isBlank()) {
                    continue;
                }
                transcript.addObject()
                        .put("role", turn.path("role").asText(""))
                        .put("message", trim(message, 500))
                        .put("at_secs", turn.path("time_in_call_secs").asInt(0));
            }
            return ToolResult.succeeded(
                    content.toString(),
                    "Read call " + id + ": " + content.path("status").asText() + ", "
                            + content.path("duration_secs").asInt() + " seconds.",
                    Duration.ZERO);
        });
    }

    // @find: list recent phone calls
    private Mono<ToolResult> listCalls(ToolInvocation invocation, JsonNode arguments, String key) {
        int limit = Math.max(1, Math.min(arguments.path("limit").asInt(10), 50));
        String agentAsked = text(arguments, "agent");
        Mono<Optional<String>> agent;
        if (agentAsked != null) {
            agent = convai.findAgent(key, agentAsked).map(found -> found.map(ElevenLabsConvai.Agent::id))
                    .map(found -> {
                        if (found.isEmpty()) {
                            throw new Plain("No ElevenLabs voice agent is called \"" + agentAsked + "\".");
                        }
                        return found;
                    });
        } else {
            String known = callerAgents.get(invocation.orgId());
            agent = known != null
                    ? Mono.just(Optional.of(known))
                    : convai.agents(key, CALLER_NAME).map(agents -> agents.stream()
                            .filter(a -> CALLER_NAME.equals(a.name()))
                            .map(ElevenLabsConvai.Agent::id)
                            .findFirst());
        }
        return agent.flatMap(id -> id.isEmpty()
                        ? Mono.just(json.createArrayNode())
                        : convai.conversations(key, id.get(), limit).map(answer -> {
                            ArrayNode items = json.createArrayNode();
                            for (JsonNode call : answer.path("conversations")) {
                                long started = call.path("start_time_unix_secs").asLong(0);
                                items.addObject()
                                        .put("call_id", call.path("conversation_id").asText(""))
                                        .put("status", call.path("status").asText(""))
                                        .put("started_at", started > 0 ? Instant.ofEpochSecond(started).toString() : "")
                                        .put("duration_secs", call.path("call_duration_secs").asInt(0))
                                        .put("call_successful", call.path("call_successful").asText(""));
                            }
                            return items;
                        }))
                .map(items -> ToolResult.succeeded(
                        list(items).toString(), "Listed " + items.size() + " recent call(s).", Duration.ZERO));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private ObjectNode list(ArrayNode items) {
        ObjectNode content = json.createObjectNode();
        content.put("count", items.size());
        content.set("items", items);
        return content;
    }

    private static String text(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private ToolResult vendorRefusal(WebClientResponseException refused) {
        int status = refused.getStatusCode().value();
        if (status == 401 || status == 403) {
            return ToolResult.failed(KEY_REFUSED);
        }
        if (status == 404) {
            return ToolResult.failed("ElevenLabs could not find that. Check the id and try again.");
        }
        String detail = "";
        try {
            JsonNode body = json.readTree(refused.getResponseBodyAsString());
            JsonNode d = body.path("detail");
            detail = d.isTextual() ? d.asText()
                    : d.isArray() ? d.path(0).path("msg").asText("") : d.path("message").asText("");
        } catch (Exception ignored) {
            // Not JSON: the status alone.
        }
        log.warn("ElevenLabs refused a phone request with status {}", status);
        return ToolResult.failed("ElevenLabs refused the request (status " + status + ")"
                + (detail.isBlank() ? "." : ": " + trim(detail, 200)));
    }

    static String trim(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
