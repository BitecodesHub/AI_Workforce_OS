// @find: tests for push recipients, who is told about an approval, four-eyes push, approval:decide push, question push recipient, PushRecipientsTest
// @what: Unit tests for PushRecipients against the real ApprovalService rules: permission, four-eyes (off, destructive, all), former members and questions.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.push.MemberPermissions;
import os.aiworkforce.orchestrator.push.PushRecipients;
import os.aiworkforce.orchestrator.push.PushSubscriptionStore;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

/**
 * Push goes exactly to the people the Approvals page would let decide: the same permission and the
 * same four-eyes rule, read from the real ApprovalService.
 */
class PushRecipientsTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID MANAGER = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID FORMER = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final UUID UNCHECKABLE = UUID.fromString("00000000-0000-7000-8000-000000000005");
    private static final UUID FINANCE = UUID.fromString("00000000-0000-7000-8000-000000000006");

    private static final Map<UUID, Optional<Set<String>>> HELD = Map.of(
            MANAGER, Optional.of(Set.of("approval:read", "approval:decide")),
            REQUESTER, Optional.of(Set.of("approval:read", "approval:decide", "task:create")),
            VIEWER, Optional.of(Set.of("approval:read")),
            FORMER, Optional.of(Set.of()),
            UNCHECKABLE, Optional.empty(),
            FINANCE, Optional.of(Set.of("approval:decide", "budget:manage")));

    private RuntimeConfigService settings;
    private PushRecipients recipients;

    @BeforeEach
    void setUp() {
        PushSubscriptionStore store = mock(PushSubscriptionStore.class);
        when(store.subscribedUsers(eq(ORG), anyInt())).thenReturn(List.copyOf(HELD.keySet()));
        MemberPermissions permissions = (orgId, userId) -> orgId.equals(ORG) ? HELD.get(userId) : Optional.empty();
        settings = mock(RuntimeConfigService.class);
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("off");
        ApprovalService approvals = new ApprovalService(
                mock(Approvals.class),
                mock(Runs.class),
                new ObjectMapper(),
                mock(AuditClient.class),
                mock(TaskProgress.class),
                mock(AgentRunner.class));
        approvals.setRuntimeConfig(settings);
        recipients = new PushRecipients(store, permissions, approvals);
    }

    private static Approval approval(String actionClass) {
        Approval approval = new Approval();
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setActionClass(actionClass);
        approval.setTool("gmail.send_message");
        approval.setSummary("Send");
        approval.setRequestedBy(REQUESTER);
        approval.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        return approval;
    }

    @Test
    @DisplayName("with the four-eyes rule off, everyone subscribed who holds approval:decide is told, the requester too")
    void ruleOff() {
        assertThat(recipients.forApproval(approval("OUTBOUND"))).containsExactlyInAnyOrder(MANAGER, REQUESTER, FINANCE);
    }

    @Test
    @DisplayName("a viewer, a former member and a person whose permissions cannot be checked are never told")
    void noPermissionNoPush() {
        assertThat(recipients.forApproval(approval("OUTBOUND"))).doesNotContain(VIEWER, FORMER, UNCHECKABLE);
    }

    @Test
    @DisplayName("with the rule on for everything, the person who asked for the work is not told")
    void ruleAll() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("all");

        assertThat(recipients.forApproval(approval("OUTBOUND"))).containsExactlyInAnyOrder(MANAGER, FINANCE);
    }

    @Test
    @DisplayName("with the rule on for removals, the requester hears about a send but not about a deletion")
    void ruleDestructive() {
        when(settings.getString(any(ConfigKey.class), any())).thenReturn("destructive");

        assertThat(recipients.forApproval(approval("OUTBOUND"))).contains(REQUESTER);
        assertThat(recipients.forApproval(approval("DESTRUCTIVE"))).containsExactlyInAnyOrder(MANAGER, FINANCE);
    }

    @Test
    @DisplayName("an approval that needs a narrower permission reaches only the people who hold it")
    void requiredPermissionIsHonoured() {
        Approval refund = approval("OUTBOUND");
        refund.setRequiredPermission("budget:manage");

        assertThat(recipients.forApproval(refund)).containsExactly(FINANCE);
    }

    @Test
    @DisplayName("an approval already decided reaches nobody")
    void decidedReachesNobody() {
        Approval decided = approval("OUTBOUND");
        decided.setStatus("approved");

        assertThat(recipients.forApproval(decided)).isEmpty();
    }

    @Test
    @DisplayName("a question reaches the person who asked for the work while they are a member, and nobody otherwise")
    void questions() {
        RunQuestion question = new RunQuestion();
        question.setOrgId(ORG);
        question.setRequestedBy(REQUESTER);
        assertThat(recipients.forQuestion(question)).containsExactly(REQUESTER);

        question.setRequestedBy(FORMER);
        assertThat(recipients.forQuestion(question)).isEmpty();

        question.setRequestedBy(null);
        assertThat(recipients.forQuestion(question)).isEmpty();
    }
}
