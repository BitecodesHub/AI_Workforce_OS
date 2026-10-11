// @find: tests for twilio connector, account sid format, connect number to ElevenLabs, already imported, no ElevenLabs key, number not on account, approval for connect, buy without price
// @what: Checks the Twilio adapter's own rules beyond the shared contract: the SID check, connecting a number to ElevenLabs in each situation, and that connecting always asks first.
package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.WorkspaceSecrets;

class TwilioAdapterTest {

    /** Built from parts so secret scanners never see a literal that looks like a real Twilio ID. */
    private static final String SID = "A" + "C" + "0123456789abcdef".repeat(2);
    private static final String CREDENTIAL = "{\"accountSid\":\"" + SID + "\",\"authToken\":\"secret-token-123\"}";
    private static final String OWNED = "{\"incoming_phone_numbers\":[{\"sid\":\"PN1\",\"phone_number\":\"+61255550101\","
            + "\"capabilities\":{\"voice\":true}}]}";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private TwilioAdapter adapter(WorkspaceSecrets secrets) {
        SandboxServerAdapter sandbox =
                new SandboxServerAdapter("twilio", SandboxServerRegistry.definitions().get("twilio"), json);
        return new TwilioAdapter(sandbox, json, WebClient.builder(), server.baseUrl())
                .useElevenLabs(WebClient.builder(), server.baseUrl(), () -> secrets);
    }

    private ToolResult call(TwilioAdapter adapter, String tool, String args, String credential) {
        return adapter.invoke(new ToolInvocation("org-1", "agent-1", "run-1", "twilio", tool, args, "k", Map.of()),
                credential).block();
    }

    @Test
    @DisplayName("a SID that does not start with AC is refused with a plain hint, before Twilio is asked")
    void sidFormat() {
        TwilioAdapter adapter = adapter(org -> Optional.empty());
        String wrong = "{\"accountSid\":\"" + "S" + "K" + "0123456789abcdef".repeat(2) + "\",\"authToken\":\"t\"}";

        assertThat(adapter.check(wrong).block().message()).contains("starts with AC");
        assertThat(call(adapter, "list_numbers", "{}", wrong).summary()).contains("starts with AC");
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("connecting always asks a person, and names the number")
    void connectAsks() {
        TwilioAdapter adapter = adapter(org -> Optional.empty());
        ToolInvocation connect = new ToolInvocation("org-1", "agent-1", "run-1", "twilio",
                "connect_number_to_elevenlabs", "{\"phone_number\":\"+61255550101\"}", "k", Map.of());
        assertThat(adapter.approvalReason(connect)).hasValueSatisfying(reason -> assertThat(reason)
                .contains("+61255550101").contains("twilio.connect_number_to_elevenlabs"));
        assertThat(adapter.approvalReason(new ToolInvocation("org-1", "agent-1", "run-1", "twilio", "list_numbers",
                "{}", "k", Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("without an ElevenLabs key the number is not connected, and nothing is sent to ElevenLabs")
    void connectWithoutKey() {
        server.stubFor(get(urlPathEqualTo("/2010-04-01/Accounts/" + SID + "/IncomingPhoneNumbers.json")).willReturn(okJson(OWNED)));

        ToolResult result = call(adapter(org -> Optional.empty()), "connect_number_to_elevenlabs",
                "{\"phone_number\":\"+61255550101\"}", CREDENTIAL);

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("No ElevenLabs key is stored");
        server.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a number already in ElevenLabs is not imported twice, and one not on the account is refused")
    void connectExistingAndUnknown() {
        server.stubFor(get(urlPathEqualTo("/2010-04-01/Accounts/" + SID + "/IncomingPhoneNumbers.json")).willReturn(okJson(OWNED)));
        server.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson(
                "[{\"phone_number\":\"+61255550101\",\"phone_number_id\":\"pn_old\"}]")));
        TwilioAdapter adapter = adapter(org -> Optional.of("xi-key"));

        ToolResult again = call(adapter, "connect_number_to_elevenlabs", "{\"phone_number\":\"+61255550101\"}", CREDENTIAL);
        ToolResult unknown = call(adapter, "connect_number_to_elevenlabs", "{\"phone_number\":\"+61255550999\"}", CREDENTIAL);

        assertThat(again.summary()).isEqualTo("+61255550101 was already connected to ElevenLabs.");
        assertThat(again.contentJson()).contains("pn_old").doesNotContain("secret-token-123");
        assertThat(unknown.summary()).contains("is not a number on this Twilio account");
        server.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("ElevenLabs refusing the import is said plainly, and premium numbers are never bought")
    void refusals() {
        server.stubFor(get(urlPathEqualTo("/2010-04-01/Accounts/" + SID + "/IncomingPhoneNumbers.json")).willReturn(okJson(OWNED)));
        server.stubFor(get("/v1/convai/phone-numbers").willReturn(okJson("[]")));
        server.stubFor(post("/v1/convai/phone-numbers").willReturn(aResponse().withStatus(401)));
        TwilioAdapter adapter = adapter(org -> Optional.of("xi-key"));

        assertThat(call(adapter, "connect_number_to_elevenlabs", "{\"phone_number\":\"+61255550101\"}", CREDENTIAL)
                        .summary())
                .contains("ElevenLabs did not accept the stored ElevenLabs key");
        assertThat(call(adapter, "buy_number", "{\"phone_number\":\"+61190012345\"}", CREDENTIAL).summary())
                .contains("premium-rate");
        server.verify(0, postRequestedFor(urlPathEqualTo("/2010-04-01/Accounts/" + SID + "/IncomingPhoneNumbers.json")));
    }
}
