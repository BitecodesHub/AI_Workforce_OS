package os.aiworkforce.mcp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * The governance path, asserted.
 *
 * <p>Everything this platform claims about safety rests on this class: that an agent cannot reach
 * a tool it was not granted, cannot exceed the scopes it was given, cannot send or delete without
 * a person deciding first, and cannot loop a tool until a vendor quota is gone.
 *
 * <p>The tests below are written as the failures they prevent, because each one has a real
 * consequence. A missing grant check is an agent with the whole tool surface. A policy that can
 * clear an approval gate is an agent that sends email unsupervised. Both look like ordinary
 * behaviour until somebody reads the audit log.
 */
class ToolGatewayTest {

    private static final String AGENT = "agent-1";
    private static final String ORG = "org-1";

    private ToolGateway gateway;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper();
        SandboxServerAdapter gmail = new SandboxServerAdapter("gmail", gmailTools(), json);
        SandboxServerAdapter calendar = new SandboxServerAdapter("calendar", calendarTools(), json);
        gateway = new ToolGateway(
                List.of(gmail, calendar), new ArgumentValidator(json), new ResiliencePresets(properties()));
    }

    // ---- What the model is shown ---------------------------------------------------------------

    @Test
    @DisplayName("a tool the agent lacks is never described to the model")
    void ungrantedToolsAreNotOffered() {
        List<ToolDefinition> offered = gateway.availableTools(List.of(
                grant("gmail", List.of("list_messages", "draft_message"), List.of("gmail.readonly", "gmail.compose"))));

        assertThat(offered)
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("list_messages", "draft_message")
                // Not merely refused when called: a model that can see send_message will keep
                // reaching for it, and every attempt costs a turn and an error a person reads.
                .doesNotContain("send_message");
    }

    @Test
    @DisplayName("a tool whose scope is missing is not offered either")
    void toolsMissingScopesAreNotOffered() {
        // The grant names the tool but the connection never got the scope it needs - which
        // happens whenever somebody declines one permission at a consent screen.
        List<ToolDefinition> offered =
                gateway.availableTools(List.of(grant("gmail", List.of("send_message"), List.of("gmail.readonly"))));

        assertThat(offered).isEmpty();
    }

    @Test
    @DisplayName("an empty tool list in a grant means the whole server")
    void emptyToolListCoversServer() {
        List<ToolDefinition> offered = gateway.availableTools(
                List.of(grant("calendar", List.of(), List.of("calendar.readonly", "calendar.events"))));

        assertThat(offered)
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("list_events", "create_event", "delete_event");
    }

    @Test
    @DisplayName("a disabled grant offers nothing, without being removed")
    void disabledGrantOffersNothing() {
        ToolGrant disabled = new ToolGrant(AGENT, "gmail", List.of(), List.of("gmail.readonly"), false, null, false);

        assertThat(gateway.availableTools(List.of(disabled))).isEmpty();
    }

    @Test
    @DisplayName("no server may register as 'person', the name questions to a person are sent under")
    void personServerNameIsReserved() {
        ObjectMapper json = new ObjectMapper();
        SandboxServerAdapter impostor = new SandboxServerAdapter("person", gmailTools(), json);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ToolGateway(
                        List.of(impostor), new ArgumentValidator(json), new ResiliencePresets(properties())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved");
    }

    // ---- The approval gate -----------------------------------------------------------------------

    @Test
    @DisplayName("sending always waits for a person, whatever the policy says")
    void outboundAlwaysNeedsApproval() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "send_message", "{\"to\":\"a@b.com\",\"subject\":\"s\",\"body\":\"b\"}"),
                List.of(grant("gmail", List.of(), List.of("gmail.send"))),
                // Policy explicitly not requiring approval. It must not be able to clear the gate:
                // an email cannot be recalled, and no configuration should be able to skip that
                // by accident.
                false);

        assertThat(decision).isInstanceOf(ApprovalDecision.AwaitApproval.class);
    }

    @Test
    @DisplayName("deleting always waits for a person")
    void destructiveAlwaysNeedsApproval() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("calendar", "delete_event", "{\"id\":\"evt-1\"}"),
                List.of(grant("calendar", List.of(), List.of("calendar.events"))),
                false);

        assertThat(decision).isInstanceOf(ApprovalDecision.AwaitApproval.class);
    }

    @Test
    @DisplayName("reading proceeds without a gate")
    void readProceeds() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "list_messages", "{}"),
                List.of(grant("gmail", List.of(), List.of("gmail.readonly"))),
                false);

        // Gating reads would make the platform useless: an agent that needs permission to look
        // something up cannot do anything unattended.
        assertThat(decision).isInstanceOf(ApprovalDecision.Proceed.class);
    }

    @Test
    @DisplayName("drafting proceeds, so only the sending waits")
    void writeProceedsWhenPolicyAllows() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "draft_message", "{\"to\":\"a@b.com\",\"subject\":\"s\",\"body\":\"b\"}"),
                List.of(grant("gmail", List.of(), List.of("gmail.compose"))),
                false);

        // The split that makes the platform usable rather than permanently blocked: an agent can
        // prepare the whole email freely, and a person only decides the irreversible part.
        assertThat(decision).isInstanceOf(ApprovalDecision.Proceed.class);
    }

    @Test
    @DisplayName("a workspace policy can add a gate a tool would not carry by itself")
    void policyCanAddAGate() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "list_messages", "{}"),
                List.of(grant("gmail", List.of(), List.of("gmail.readonly"))),
                true);

        assertThat(decision).isInstanceOf(ApprovalDecision.AwaitApproval.class);
    }

    @Test
    @DisplayName("a grant can add a gate for one server without affecting the others")
    void grantCanRequireApproval() {
        ToolGrant cautious = new ToolGrant(AGENT, "gmail", List.of(), List.of("gmail.readonly"), true, null, true);

        assertThat(gateway.evaluate(invocation("gmail", "list_messages", "{}"), List.of(cautious), false))
                .isInstanceOf(ApprovalDecision.AwaitApproval.class);
    }

    // ---- Refusals ----------------------------------------------------------------------------------

    @Test
    @DisplayName("an ungranted tool is refused, and the refusal names what is needed")
    void ungrantedToolIsRefused() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "send_message", "{}"),
                List.of(grant("gmail", List.of("list_messages"), List.of("gmail.readonly"))),
                false);

        assertThat(decision).isInstanceOfSatisfying(ApprovalDecision.Refuse.class, refuse -> assertThat(refuse.reason())
                .contains("gmail.send_message"));
    }

    @Test
    @DisplayName("a missing scope is named, so an administrator knows what to add")
    void missingScopeIsNamed() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "send_message", "{}"),
                List.of(grant("gmail", List.of("send_message"), List.of("gmail.readonly"))),
                false);

        assertThat(decision)
                .isInstanceOfSatisfying(
                        ApprovalDecision.Refuse.class,
                        refuse ->
                                // "Permission denied" alone sends somebody hunting through a consent screen.
                                assertThat(refuse.reason()).contains("gmail.send"));
    }

    @Test
    @DisplayName("a tool on a server that is not connected is refused")
    void unknownServerIsRefused() {
        ApprovalDecision decision = gateway.evaluate(invocation("salesforce", "create_lead", "{}"), List.of(), false);

        assertThat(decision).isInstanceOfSatisfying(ApprovalDecision.Refuse.class, refuse -> assertThat(refuse.reason())
                .contains("salesforce"));
    }

    @Test
    @DisplayName("a tool the server does not offer is refused")
    void unknownToolIsRefused() {
        ApprovalDecision decision = gateway.evaluate(
                invocation("gmail", "delete_everything", "{}"),
                List.of(grant("gmail", List.of(), List.of("gmail.readonly"))),
                false);

        assertThat(decision).isInstanceOf(ApprovalDecision.Refuse.class);
    }

    // ---- Argument validation --------------------------------------------------------------------------

    @Test
    @DisplayName("arguments that do not match the schema are refused before anything is sent")
    void invalidArgumentsAreCaughtBeforeTheCall() {
        ToolResult result = gateway.invoke(
                        // The schema requires to, subject and body. A model producing two of three
                        // is an ordinary occurrence, not an exception.
                        invocation("gmail", "draft_message", "{\"to\":\"a@b.com\"}"), ApprovalDecision.PROCEED, null)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("schema");
    }

    @Test
    @DisplayName("arguments that are not JSON at all are reported in words the model can act on")
    void malformedJsonIsReportedUsefully() {
        ToolResult result = gateway.invoke(
                        invocation("gmail", "draft_message", "Here is the email: {to: a@b.com"),
                        ApprovalDecision.PROCEED,
                        null)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        // The model reads this and gets one chance to correct itself, so it says what to do.
        assertThat(result.summary()).contains("JSON object");
    }

    @Test
    @DisplayName("valid arguments run and the sandbox says nothing left the machine")
    void validArgumentsRun() {
        ToolResult result = gateway.invoke(
                        invocation(
                                "gmail",
                                "draft_message",
                                "{\"to\":\"a@b.com\",\"subject\":\"Welcome\",\"body\":\"Hello\"}"),
                        ApprovalDecision.PROCEED,
                        null)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.summary()).contains("Nothing left this machine");
    }

    // ---- Invocation guards -------------------------------------------------------------------------------

    @Test
    @DisplayName("a refused decision cannot be turned into a call")
    void refusedDecisionDoesNotReachTheProvider() {
        ToolResult result = gateway.invoke(
                        invocation("gmail", "send_message", "{}"), new ApprovalDecision.Refuse("not granted"), null)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        // The decision is passed in rather than re-derived, so a call cannot reach a provider by
        // a path that skipped the gate.
        assertThat(result.status()).isEqualTo(ToolResult.Status.BLOCKED);
    }

    @Test
    @DisplayName("an undecided approval cannot be turned into a call either")
    void pendingApprovalDoesNotReachTheProvider() {
        ToolResult result = gateway.invoke(
                        invocation("gmail", "send_message", "{}"),
                        new ApprovalDecision.AwaitApproval("send an email", "approval:decide"),
                        null)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolResult.Status.BLOCKED);
    }

    @Test
    @DisplayName("a per-run ceiling stops an agent looping a tool")
    void runCeilingIsEnforced() {
        ToolGrant capped = new ToolGrant(AGENT, "gmail", List.of(), List.of("gmail.readonly"), false, 2, true);
        ToolInvocation call = invocation("gmail", "list_messages", "{}");

        for (int i = 0; i < 2; i++) {
            assertThat(gateway.evaluate(call, List.of(capped), false)).isInstanceOf(ApprovalDecision.Proceed.class);
            gateway.invoke(call, ApprovalDecision.PROCEED, null).block(Duration.ofSeconds(5));
        }

        // The third is refused: one looping agent must not be able to exhaust a workspace's
        // quota with a vendor.
        assertThat(gateway.evaluate(call, List.of(capped), false)).isInstanceOf(ApprovalDecision.Refuse.class);

        // Releasing the run resets the counter, so the next task starts with a clean budget.
        gateway.releaseRun("run-1");
        assertThat(gateway.evaluate(call, List.of(capped), false)).isInstanceOf(ApprovalDecision.Proceed.class);
    }

    @Test
    @DisplayName("an unknown outcome is never retried for a tool that cannot be repeated")
    void indeterminateOutcomeIsNotRetried() {
        // The sandbox is asked to time out. Sending is not idempotent, so the result must say
        // the outcome is unknown rather than that the call failed.
        ToolResult result = gateway.invoke(
                        invocation(
                                "gmail",
                                "send_message",
                                "{\"to\":\"[[fault:timeout]]\",\"subject\":\"s\",\"body\":\"b\"}"),
                        ApprovalDecision.PROCEED,
                        null)
                .block(Duration.ofSeconds(10));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ToolResult.Status.INDETERMINATE);
        // The model is told explicitly not to repeat it, because the email may already be sent.
        assertThat(result.forModel()).contains("Do not repeat this action");
    }

    // ---- Fixtures ----------------------------------------------------------------------------------------

    private static ToolGrant grant(String server, List<String> tools, List<String> scopes) {
        return new ToolGrant(AGENT, server, tools, scopes, false, null, true);
    }

    private static ToolInvocation invocation(String server, String tool, String arguments) {
        return new ToolInvocation(ORG, AGENT, "run-1", server, tool, arguments, "idem-1", Map.of());
    }

    private static List<ToolDefinition> gmailTools() {
        String schema = "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},"
                + "\"subject\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},"
                + "\"required\":[\"to\",\"subject\",\"body\"]}";
        return List.of(
                new ToolDefinition(
                        "gmail",
                        "list_messages",
                        "List messages",
                        "{\"type\":\"object\",\"properties\":{}}",
                        ToolSpec.SideEffect.READ,
                        List.of("gmail.readonly"),
                        true,
                        Duration.ofSeconds(5),
                        60),
                new ToolDefinition(
                        "gmail",
                        "draft_message",
                        "Prepare an email",
                        schema,
                        ToolSpec.SideEffect.WRITE,
                        List.of("gmail.compose"),
                        false,
                        Duration.ofSeconds(5),
                        30),
                new ToolDefinition(
                        "gmail",
                        "send_message",
                        "Send an email",
                        schema,
                        ToolSpec.SideEffect.OUTBOUND,
                        List.of("gmail.send"),
                        false,
                        Duration.ofMillis(300),
                        20));
    }

    private static List<ToolDefinition> calendarTools() {
        return List.of(
                new ToolDefinition(
                        "calendar",
                        "list_events",
                        "List events",
                        "{\"type\":\"object\",\"properties\":{}}",
                        ToolSpec.SideEffect.READ,
                        List.of("calendar.readonly"),
                        true,
                        Duration.ofSeconds(5),
                        60),
                new ToolDefinition(
                        "calendar",
                        "create_event",
                        "Create an event",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}",
                        ToolSpec.SideEffect.WRITE,
                        List.of("calendar.events"),
                        false,
                        Duration.ofSeconds(5),
                        30),
                new ToolDefinition(
                        "calendar",
                        "delete_event",
                        "Remove an event",
                        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                        ToolSpec.SideEffect.DESTRUCTIVE,
                        List.of("calendar.events"),
                        false,
                        Duration.ofSeconds(5),
                        5));
    }

    static PlatformProperties properties() {
        return new PlatformProperties(
                PlatformProperties.Environment.TEST,
                "test",
                "0.0.1",
                new PlatformProperties.Security(
                        "aiwos",
                        "aiwos-api",
                        "http://localhost/jwks",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        null,
                        null,
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        "test-secret",
                        "test-internal-secret",
                        new PlatformProperties.Argon2(1, 1024, 1, 16, 32),
                        new PlatformProperties.Encryption(null, "test", "AES/GCM/NoPadding", 12, 128),
                        12,
                        8,
                        Duration.ofMinutes(15),
                        false),
                new PlatformProperties.Http(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        100,
                        20,
                        10_485_760,
                        List.of("http://localhost"),
                        true,
                        Duration.ofMinutes(30)),
                new PlatformProperties.RateLimit(true, 600, 60, 30, 10, true),
                new PlatformProperties.Events(
                        false,
                        "aiwos",
                        3,
                        (short) 1,
                        Duration.ofSeconds(30),
                        4,
                        List.of(Duration.ofSeconds(1)),
                        true,
                        Duration.ofDays(7)),
                new PlatformProperties.Resilience(
                        50,
                        10,
                        5,
                        Duration.ofSeconds(30),
                        3,
                        3,
                        Duration.ofMillis(1),
                        Duration.ofMillis(5),
                        2.0,
                        false,
                        25,
                        Duration.ofSeconds(60),
                        Map.of()),
                new PlatformProperties.Observability(
                        1.0, List.of("password"), false),
                new PlatformProperties.RuntimeConfig(false, Duration.ofSeconds(60), "channel", true),
                new PlatformProperties.Services(
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost",
                        "http://localhost"));
    }
}
