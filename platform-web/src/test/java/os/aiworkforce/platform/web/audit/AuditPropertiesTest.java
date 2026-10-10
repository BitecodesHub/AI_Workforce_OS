// @find: tests for audit properties, relay interval default, audit enabled flag
// @what: Checks the audit delivery settings and their defaults.
package os.aiworkforce.platform.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditPropertiesTest {

    private final AuditProperties properties = AuditClientTest.properties(true);

    @Test
    @DisplayName("the wait doubles from ten seconds, caps at thirty, and becomes the slow retry once attempts run out")
    void backoff() {
        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backoffAfter(19)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backoffAfter(20)).isEqualTo(Duration.ofHours(1));
        assertThat(properties.backoffAfter(500)).isEqualTo(Duration.ofHours(1));
    }
}
