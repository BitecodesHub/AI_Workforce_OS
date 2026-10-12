// @find: tests for push endpoints allow list, device labels, push message text, push rate limiter, PushPartsTest
// @what: Unit tests for the small push parts: which endpoints are allowed, how a device is named, what a message says and how the limit slides.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;

/** Where a push may go, what it is called, what it says, and how often. */
class PushPartsTest {

    @Test
    @DisplayName("only https endpoints at a browser push service, or a host the operator added, are allowed")
    void endpointRules() {
        PushEndpoints endpoints = new PushEndpoints(PushEndpoints.hostsWith(" push.example.org , "), false);

        assertThat(endpoints.check("https://fcm.googleapis.com/fcm/send/abc").getHost()).isEqualTo("fcm.googleapis.com");
        assertThat(endpoints.check("https://web.push.apple.com/QGv").getHost()).isEqualTo("web.push.apple.com");
        assertThat(endpoints.check("https://push.example.org/x").getHost()).isEqualTo("push.example.org");
        for (String refused : List.of(
                "http://fcm.googleapis.com/fcm/send/abc",
                "https://localhost:8443/x",
                "https://127.0.0.1/x",
                "https://evilfcm.googleapis.com.attacker.example/x",
                "https://notfcm.googleapis.co/x",
                "https://u:p@fcm.googleapis.com/x",
                "ftp://fcm.googleapis.com/x",
                "not a url",
                "")) {
            assertThatThrownBy(() -> endpoints.check(refused)).as(refused).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> endpoints.check("https://fcm.googleapis.com/" + "a".repeat(PushEndpoints.MAX_LENGTH)))
                .hasMessage("is too long");
    }

    @Test
    @DisplayName("in a deployed environment a name that cannot be looked up is a passing failure, a private address a refusal")
    void strictChecksTellTemporaryFromFinal() throws Exception {
        PushEndpoints noNameService = new PushEndpoints(PushEndpoints.DEFAULT_HOSTS, true, host -> {
            throw new java.net.UnknownHostException(host);
        });
        assertThatThrownBy(() -> noNameService.check("https://fcm.googleapis.com/fcm/send/abc"))
                .isInstanceOf(PushEndpoints.Unresolved.class)
                .isNotInstanceOf(IllegalArgumentException.class);

        java.net.InetAddress inside = java.net.InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 9});
        PushEndpoints rebound = new PushEndpoints(PushEndpoints.DEFAULT_HOSTS, true, host -> new java.net.InetAddress[] {inside});
        assertThatThrownBy(() -> rebound.check("https://fcm.googleapis.com/fcm/send/abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public address");

        java.net.InetAddress outside = java.net.InetAddress.getByAddress(new byte[] {(byte) 142, (byte) 250, 70, 106});
        PushEndpoints fine = new PushEndpoints(PushEndpoints.DEFAULT_HOSTS, true, host -> new java.net.InetAddress[] {outside});
        assertThat(fine.check("https://fcm.googleapis.com/fcm/send/abc").getHost()).isEqualTo("fcm.googleapis.com");
    }

    @Test
    @DisplayName("a device is named by browser and platform, and an unknown one as this browser")
    void deviceLabels() {
        assertThat(DeviceLabels.fromUserAgent(
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Safari on iPhone");
        assertThat(DeviceLabels.fromUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 Edg/129.0"))
                .isEqualTo("Edge on Windows");
        assertThat(DeviceLabels.fromUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:131.0) Gecko/20100101 Firefox/131.0"))
                .isEqualTo("Firefox on Mac");
        assertThat(DeviceLabels.fromUserAgent("curl/8.0")).isEqualTo(DeviceLabels.UNKNOWN);
        assertThat(DeviceLabels.fromUserAgent(null)).isEqualTo(DeviceLabels.UNKNOWN);
    }

    @Test
    @DisplayName("an approval's message names the action in words, links to its card, and lasts until the approval expires")
    void approvalMessage() {
        Instant now = Instant.parse("2026-10-12T09:00:00Z");
        Approval approval = new Approval();
        approval.setTool("slack.post_message");
        approval.setActionClass("OUTBOUND");
        approval.setExpiresAt(now.plus(Duration.ofDays(3)));

        PushMessage message = PushMessage.forApproval(approval, now);

        assertThat(message.url()).isEqualTo("/approvals#approval-" + approval.getId());
        assertThat(message.body()).doesNotContain("slack.post_message").contains("Slack").endsWith("Open it to approve or reject.");
        assertThat(message.ttlSeconds()).isEqualTo(PushMessage.MAX_TTL);

        approval.setTool(null);
        approval.setActionClass("WRITE");
        approval.setExpiresAt(now.minusSeconds(5));
        PushMessage odd = PushMessage.forApproval(approval, now);
        assertThat(odd.body()).isEqualTo("An action. Open it to approve or reject.");
        assertThat(odd.ttlSeconds()).isEqualTo(PushMessage.MIN_TTL);
    }

    @Test
    @DisplayName("the limit counts per person over a sliding window")
    void limiterSlides() {
        VapidKeyStoreTest.MutableClock clock = new VapidKeyStoreTest.MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
        PushRateLimiter limiter = new PushRateLimiter(2, Duration.ofMinutes(1), clock);
        UUID maya = UUID.randomUUID();
        UUID priya = UUID.randomUUID();

        assertThat(limiter.tryAcquire(maya)).isTrue();
        clock.now = clock.now.plusSeconds(30);
        assertThat(limiter.tryAcquire(maya)).isTrue();
        assertThat(limiter.tryAcquire(maya)).isFalse();
        assertThat(limiter.tryAcquire(priya)).isTrue();

        clock.now = clock.now.plusSeconds(31);
        assertThat(limiter.tryAcquire(maya)).isTrue();
        assertThat(limiter.tryAcquire(maya)).isFalse();
    }
}
