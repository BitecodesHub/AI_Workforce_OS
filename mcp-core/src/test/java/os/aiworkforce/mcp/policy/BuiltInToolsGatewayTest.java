// @find: tests for built in connectors through the gateway, browser click needs approval, submit form needs approval, place call needs approval, voice agent changes ask first, twilio buy release connect ask first, open page proceeds, fill field proceeds
// @what: Checks the gateway parks click_button, submit_form and place_call for a person even on a grant that asks for no approval, and lets reads and typing through.
package os.aiworkforce.mcp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.platform.resilience.ResiliencePresets;

class BuiltInToolsGatewayTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ToolGateway gateway = new ToolGateway(
            SandboxServerRegistry.servers(json, WebClient.builder(), false),
            new ArgumentValidator(json),
            new ResiliencePresets(ToolGatewayTest.properties()));
    private final List<ToolGrant> grants = List.of(
            new ToolGrant("agent-1", "browser", List.of(), List.of(), false, null, true),
            new ToolGrant("agent-1", "phone", List.of(), List.of(), false, null, true),
            new ToolGrant("agent-1", "twilio", List.of(), List.of(), false, null, true));

    @Test
    @DisplayName("click_button, submit_form and place_call wait for a person even when the grant asks for no approval")
    void outboundWaits() {
        assertThat(decide("browser", "click_button", "{\"text\":\"Buy now\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("browser", "submit_form", "{}")).isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("phone", "place_call", "{\"to_number\":\"+61299990000\",\"purpose\":\"Check a booking\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("phone", "create_voice_agent", "{\"name\":\"Sam\",\"prompt\":\"Be brief.\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("phone", "update_voice_agent", "{\"agent\":\"Sam\",\"prompt\":\"Be briefer.\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("twilio", "buy_number", "{\"phone_number\":\"+61255550188\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("twilio", "release_number", "{\"phone_number\":\"+61255550188\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
        assertThat(decide("twilio", "connect_number_to_elevenlabs", "{\"phone_number\":\"+61255550188\"}"))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
    }

    @Test
    @DisplayName("opening, reading, following links, typing and checking calls go ahead without asking")
    void readsProceed() {
        assertThat(decide("browser", "open_page", "{\"url\":\"https://example.com\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("browser", "read_page", "{}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("browser", "follow_link", "{\"linkText\":\"More\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("browser", "fill_field", "{\"field\":\"q\",\"value\":\"x\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("browser", "search_site", "{\"query\":\"lofi music\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("phone", "get_call", "{\"call_id\":\"conv_1\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("phone", "list_voice_agents", "{}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("twilio", "list_numbers", "{}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("twilio", "search_available_numbers", "{\"country_code\":\"AU\"}")).isEqualTo(ApprovalDecision.PROCEED);
        assertThat(decide("browser", "open_page", "{}")).isInstanceOf(ApprovalDecision.Invalid.class);
    }

    @Test
    @DisplayName("a browser submit's approval says which page it acts on, since its own arguments are empty")
    void submitApprovalNamesThePage() {
        assertThat(decide("browser", "submit_form", "{}"))
                .isInstanceOfSatisfying(ApprovalDecision.AwaitApproval.class, await -> assertThat(await.reason())
                        .isEqualTo("Send something outside the workspace using browser.submit_form. Submits the form"
                                + " on the open page. Nothing was typed into the page."));
        assertThat(decide("phone", "place_call", "{\"to_number\":\"+61299990000\",\"purpose\":\"Check a booking\"}"))
                .isInstanceOfSatisfying(ApprovalDecision.AwaitApproval.class, await -> assertThat(await.reason())
                        .isEqualTo("Send something outside the workspace using phone.place_call"));
    }

    @Test
    @DisplayName("the tools whose every call asks first say so, so the Connectors page can mark them; reads do not")
    void asksEveryCallMatchesTheGate() {
        assertThat(gateway.adapter("phone").orElseThrow().asksEveryCall("create_voice_agent")).isTrue();
        assertThat(gateway.adapter("phone").orElseThrow().asksEveryCall("update_voice_agent")).isTrue();
        assertThat(gateway.adapter("twilio").orElseThrow().asksEveryCall("connect_number_to_elevenlabs")).isTrue();
        assertThat(gateway.adapter("phone").orElseThrow().asksEveryCall("list_voice_agents")).isFalse();
        assertThat(gateway.adapter("twilio").orElseThrow().asksEveryCall("list_numbers")).isFalse();
        assertThat(gateway.adapter("browser").orElseThrow().asksEveryCall("fill_field")).isFalse();
    }

    private ApprovalDecision decide(String server, String tool, String arguments) {
        return gateway.evaluate(
                new ToolInvocation("org-1", "agent-1", "run-1", server, tool, arguments, "k", Map.of()), grants, false);
    }
}
