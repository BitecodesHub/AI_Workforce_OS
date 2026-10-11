// @find: tests for phone calls connector, place call, ElevenLabs outbound call, no phone number connected, no ElevenLabs key, caller agent created once, E.164 check, premium numbers refused, get call transcript, sip trunk
// @what: Checks the phone adapter against a mocked ElevenLabs: the no-key and no-number messages, number checks, a successful Twilio and SIP call, and reading a call back.
package os.aiworkforce.mcp.builtin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.WorkspaceSecrets;

class PhoneAdapterTest {

    private static final String CALL = "{\"to_number\":\"+61 2 9999 0000\",\"purpose\":\"Confirm the delivery time\"}";
    private static final String TWILIO_NUMBER = "[{\"phone_number\":\"+61200000000\",\"label\":\"Office\","
            + "\"phone_number_id\":\"pn_1\",\"provider\":\"twilio\"}]";

    private final ObjectMapper json = new ObjectMapper();
    private final List<ToolDefinition> tools = SandboxServerRegistry.definitions().get("phone");
    private WireMockServer elevenLabs;
    private PhoneAdapter adapter;

    @BeforeEach
    void start() {
        elevenLabs = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        elevenLabs.start();
        adapter = adapterWith(org -> Optional.of("xi-test-key"));
    }

    @AfterEach
    void stop() {
        elevenLabs.stop();
    }

    @Test
    @DisplayName("placing a call always asks a person; checking calls is a read")
    void sideEffects() {
        Map<String, ToolSpec.SideEffect> effects = new java.util.LinkedHashMap<>();
        tools.forEach(tool -> effects.put(tool.name(), tool.sideEffect()));
        assertThat(effects).containsExactly(
                Map.entry("list_voice_agents", ToolSpec.SideEffect.READ),
                Map.entry("create_voice_agent", ToolSpec.SideEffect.WRITE),
                Map.entry("update_voice_agent", ToolSpec.SideEffect.WRITE),
                Map.entry("list_phone_numbers", ToolSpec.SideEffect.READ),
                Map.entry("place_call", ToolSpec.SideEffect.OUTBOUND),
                Map.entry("get_call", ToolSpec.SideEffect.READ),
                Map.entry("list_calls", ToolSpec.SideEffect.READ));
        assertThat(adapter.approvalReason(invocation("create_voice_agent", "{\"name\":\"Sam\",\"prompt\":\"x\"}")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("Sam").contains("phone.create_voice_agent"));
        assertThat(adapter.approvalReason(invocation("update_voice_agent", "{\"agent\":\"Sam\"}"))).isPresent();
        assertThat(adapter.approvalReason(invocation("list_voice_agents", "{}"))).isEmpty();
    }

    @Test
    @DisplayName("an account with no phone number gets the exact plain message, and no agent is created")
    void noPhoneNumber() {
        elevenLabs.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson("[]")));

        ToolResult result = call("place_call", CALL);

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).isEqualTo(
                "No phone number is connected in ElevenLabs, so no call can be placed. Connect Twilio in Connectors"
                        + " and ask an agent to connect a number, or add one in ElevenLabs under Phone numbers, then"
                        + " try again.");
        assertThat(result.forModel()).contains("No phone number is connected in ElevenLabs");
        elevenLabs.verify(getRequestedFor(urlEqualTo("/v1/convai/phone-numbers")).withHeader("xi-api-key", equalTo("xi-test-key")));
        elevenLabs.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("with no key stored, or the store down, nothing is sent to ElevenLabs")
    void noKey() {
        assertThat(adapterWith(org -> Optional.empty()).invoke(invocation("place_call", CALL), null).block().summary())
                .isEqualTo(PhoneAdapter.NO_KEY);
        assertThat(adapterWith(org -> {
                    throw new IllegalStateException("down");
                }).invoke(invocation("place_call", CALL), null).block().summary())
                .isEqualTo(PhoneAdapter.STORE_DOWN);
        assertThat(adapterWith(null).invoke(invocation("list_calls", "{}"), null).block().summary())
                .isEqualTo(PhoneAdapter.NO_KEY);
        assertThat(elevenLabs.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("numbers must be E.164, and premium-rate ranges and short codes are refused before anything is sent")
    void numberChecks() {
        for (String bad : List.of("0299990000", "+61 190 012 345", "+1 900 555 0100", "000", "+44 909 879 0000",
                "+979123456789", "112", "+61-2-9999")) {
            ToolResult result = call("place_call", "{\"to_number\":\"" + bad + "\",\"purpose\":\"x\"}");
            assertThat(result.summary()).as(bad).doesNotContain("No ElevenLabs key");
            assertThat(result.status()).as(bad).isEqualTo(ToolResult.Status.FAILED);
        }
        assertThat(PhoneNumbers.problem(PhoneNumbers.normalise("+61 (2) 9999-0000"))).isNull();
        assertThat(PhoneNumbers.problem("+14155550100")).isNull();
        assertThat(elevenLabs.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a call uses the first number, makes the caller agent once, and passes the purpose as an override")
    void placesATwilioCall() throws Exception {
        elevenLabs.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson(TWILIO_NUMBER)));
        elevenLabs.stubFor(get(urlPathEqualTo("/v1/convai/agents")).willReturn(okJson("{\"agents\":[]}")));
        elevenLabs.stubFor(post("/v1/convai/agents/create").willReturn(okJson("{\"agent_id\":\"agent_caller\"}")));
        elevenLabs.stubFor(post("/v1/convai/twilio/outbound-call").willReturn(okJson(
                "{\"success\":true,\"message\":\"ok\",\"conversation_id\":\"conv_123\",\"callSid\":\"CA1\"}")));

        ToolResult first = call("place_call", CALL);
        ToolResult second = call("place_call", CALL);

        assertThat(first.status()).as(first.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(first.summary()).isEqualTo("Started a call to +61299990000 from +61200000000.");
        JsonNode content = json.readTree(first.contentJson());
        assertThat(content.path("call_id").asText()).isEqualTo("conv_123");
        assertThat(second.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        elevenLabs.verify(1, postRequestedFor(urlEqualTo("/v1/convai/agents/create"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("AI Workforce caller")))
                .withRequestBody(matchingJsonPath(
                        "$.platform_settings.overrides.conversation_config_override.agent.prompt.prompt", equalTo("true"))));
        elevenLabs.verify(2, postRequestedFor(urlEqualTo("/v1/convai/twilio/outbound-call"))
                .withRequestBody(matchingJsonPath("$.agent_id", equalTo("agent_caller")))
                .withRequestBody(matchingJsonPath("$.agent_phone_number_id", equalTo("pn_1")))
                .withRequestBody(matchingJsonPath("$.to_number", equalTo("+61299990000")))
                .withRequestBody(matchingJsonPath(
                        "$.conversation_initiation_client_data.conversation_config_override.agent.first_message",
                        equalTo(PhoneAdapter.DEFAULT_FIRST_MESSAGE))));
        assertThat(elevenLabs.findAll(postRequestedFor(urlEqualTo("/v1/convai/twilio/outbound-call"))).get(0)
                        .getBodyAsString())
                .contains("Confirm the delivery time");
    }

    @Test
    @DisplayName("a SIP trunk number is dialled through the SIP endpoint, reusing an existing caller agent")
    void placesASipCall() {
        elevenLabs.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson(
                "[{\"phone_number\":\"+61200000001\",\"label\":\"SIP\",\"phone_number_id\":\"pn_sip\",\"provider\":\"sip_trunk\"}]")));
        elevenLabs.stubFor(get(urlPathEqualTo("/v1/convai/agents")).willReturn(okJson(
                "{\"agents\":[{\"agent_id\":\"agent_old\",\"name\":\"AI Workforce caller\"}]}")));
        elevenLabs.stubFor(post("/v1/convai/sip-trunk/outbound-call").willReturn(okJson(
                "{\"success\":true,\"message\":\"ok\",\"conversation_id\":\"conv_sip\",\"sip_call_id\":\"s1\"}")));

        ToolResult result = call("place_call", CALL.replace("}", ",\"first_message\":\"Hi, quick question.\"}"));

        assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        elevenLabs.verify(0, postRequestedFor(urlEqualTo("/v1/convai/agents/create")));
        elevenLabs.verify(postRequestedFor(urlEqualTo("/v1/convai/sip-trunk/outbound-call"))
                .withRequestBody(matchingJsonPath("$.agent_id", equalTo("agent_old")))
                .withRequestBody(matchingJsonPath(
                        "$.conversation_initiation_client_data.conversation_config_override.agent.first_message",
                        equalTo("Hi, quick question."))));
    }

    @Test
    @DisplayName("get_call reads the status, length, summary and transcript; a refused key is said plainly")
    void readsACall() throws Exception {
        elevenLabs.stubFor(get("/v1/convai/conversations/conv_123").willReturn(okJson(
                "{\"status\":\"done\",\"metadata\":{\"call_duration_secs\":42},\"analysis\":{\"transcript_summary\":"
                        + "\"Delivery confirmed for 3pm.\",\"call_successful\":\"success\"},\"transcript\":[{\"role\":"
                        + "\"agent\",\"message\":\"Hello\",\"time_in_call_secs\":0},{\"role\":\"user\",\"message\":"
                        + "\"Hi\",\"time_in_call_secs\":2}]}")));
        elevenLabs.stubFor(get("/v1/convai/conversations/conv_bad").willReturn(aResponse().withStatus(401)));

        ToolResult result = call("get_call", "{\"call_id\":\"conv_123\"}");

        assertThat(result.summary()).isEqualTo("Read call conv_123: done, 42 seconds.");
        JsonNode content = json.readTree(result.contentJson());
        assertThat(content.path("summary").asText()).isEqualTo("Delivery confirmed for 3pm.");
        assertThat(content.path("transcript")).hasSize(2);
        assertThat(call("get_call", "{\"call_id\":\"conv_bad\"}").summary()).isEqualTo(PhoneAdapter.KEY_REFUSED);
    }

    @Test
    @DisplayName("a named agent places the call with its own prompt; an unknown one is refused before dialling")
    void callsWithANamedAgent() {
        elevenLabs.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson(TWILIO_NUMBER)));
        elevenLabs.stubFor(get(urlPathEqualTo("/v1/convai/agents")).willReturn(okJson(
                "{\"agents\":[{\"agent_id\":\"ag_sales\",\"name\":\"Sales caller\"}]}")));
        elevenLabs.stubFor(post("/v1/convai/twilio/outbound-call").willReturn(okJson(
                "{\"success\":true,\"conversation_id\":\"conv_9\"}")));

        ToolResult named = call("place_call", "{\"to_number\":\"+61299990000\",\"agent\":\"sales caller\"}");
        ToolResult unknown = call("place_call", "{\"to_number\":\"+61299990000\",\"agent\":\"Nobody\"}");
        ToolResult neither = call("place_call", "{\"to_number\":\"+61299990000\"}");

        assertThat(named.status()).as(named.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(elevenLabs.findAll(postRequestedFor(urlEqualTo("/v1/convai/twilio/outbound-call"))).get(0)
                        .getBodyAsString())
                .contains("ag_sales")
                .doesNotContain("conversation_initiation_client_data");
        assertThat(unknown.summary()).contains("No ElevenLabs voice agent is called \"Nobody\"");
        assertThat(neither.summary()).contains("Say what the call is for");
        elevenLabs.verify(1, postRequestedFor(urlEqualTo("/v1/convai/twilio/outbound-call")));
    }

    @Test
    @DisplayName("voice agents are listed, created with per-call overrides allowed, and updated by name")
    void manageAgents() throws Exception {
        elevenLabs.stubFor(get(urlPathEqualTo("/v1/convai/agents")).willReturn(okJson(
                "{\"agents\":[{\"agent_id\":\"ag_1\",\"name\":\"Receptionist\"}]}")));
        elevenLabs.stubFor(post("/v1/convai/agents/create").willReturn(okJson("{\"agent_id\":\"ag_2\"}")));
        elevenLabs.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(urlEqualTo("/v1/convai/agents/ag_1"))
                .willReturn(okJson("{}")));

        ToolResult listed = call("list_voice_agents", "{}");
        ToolResult created = call("create_voice_agent",
                "{\"name\":\"Bookings\",\"prompt\":\"Book tables.\",\"voice_id\":\"v1\",\"language\":\"en\"}");
        ToolResult duplicate = call("create_voice_agent", "{\"name\":\"receptionist\",\"prompt\":\"x\"}");
        ToolResult updated = call("update_voice_agent", "{\"agent\":\"Receptionist\",\"first_message\":\"Hi there.\"}");

        assertThat(json.readTree(listed.contentJson()).path("items").get(0).path("agent_id").asText()).isEqualTo("ag_1");
        assertThat(created.summary()).isEqualTo("Created the ElevenLabs voice agent \"Bookings\".");
        elevenLabs.verify(postRequestedFor(urlEqualTo("/v1/convai/agents/create"))
                .withRequestBody(matchingJsonPath("$.conversation_config.tts.voice_id", equalTo("v1")))
                .withRequestBody(matchingJsonPath(
                        "$.platform_settings.overrides.conversation_config_override.agent.first_message", equalTo("true"))));
        assertThat(duplicate.summary()).contains("already exists");
        assertThat(updated.summary()).isEqualTo("Updated the ElevenLabs voice agent \"Receptionist\".");
        elevenLabs.verify(com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor(urlEqualTo("/v1/convai/agents/ag_1"))
                .withRequestBody(matchingJsonPath("$.conversation_config.agent.first_message", equalTo("Hi there.")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*prompt.*")));
    }

    @Test
    @DisplayName("the numbers in ElevenLabs are listed with their assigned agent, and an empty list says so")
    void listsPhoneNumbers() throws Exception {
        elevenLabs.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson(
                "[{\"phone_number\":\"+61200000000\",\"phone_number_id\":\"pn_1\",\"provider\":\"twilio\","
                        + "\"label\":\"Office\",\"assigned_agent\":{\"agent_id\":\"ag_1\",\"agent_name\":\"Receptionist\"}}]")));

        ToolResult result = call("list_phone_numbers", "{}");

        assertThat(result.summary()).isEqualTo("Listed 1 phone number(s) connected in ElevenLabs.");
        assertThat(json.readTree(result.contentJson()).path("items").get(0).path("assigned_agent").asText())
                .isEqualTo("Receptionist");
    }

    private PhoneAdapter adapterWith(WorkspaceSecrets secrets) {
        return new PhoneAdapter(tools, json, WebClient.builder(), elevenLabs.baseUrl(), () -> secrets);
    }

    private ToolResult call(String tool, String arguments) {
        return adapter.invoke(invocation(tool, arguments), null).block(Duration.ofSeconds(20));
    }

    private static ToolInvocation invocation(String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", "run-1", "phone", tool, arguments, "run-1:c1", Map.of());
    }
}
