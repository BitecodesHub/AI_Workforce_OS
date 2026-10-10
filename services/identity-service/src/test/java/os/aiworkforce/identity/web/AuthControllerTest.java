// @find: tests for AuthController, client address, forwarded for, address literal
// @what: Tests of client address extraction used by sign-in.
package os.aiworkforce.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Where a session came from. The column is INET, so anything that is not an address literal must
 * be dropped here rather than fail the sign-in at insert time.
 */
class AuthControllerTest {

    @Test
    @DisplayName("takes the first forwarded address, or the connection's own")
    void clientAddress() {
        MockHttpServletRequest behindProxy = new MockHttpServletRequest();
        behindProxy.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.5");
        behindProxy.setRemoteAddr("10.0.0.5");
        assertThat(AuthController.clientAddress(behindProxy)).isEqualTo("203.0.113.9");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("0:0:0:0:0:0:0:1");
        assertThat(AuthController.clientAddress(direct)).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    @DisplayName("keeps address literals and drops everything else")
    void addressLiteral() {
        assertThat(AuthController.addressLiteral("198.51.100.4")).isEqualTo("198.51.100.4");
        assertThat(AuthController.addressLiteral("[2001:db8::1]")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(AuthController.addressLiteral("fe80::1%en0")).isEqualTo("fe80:0:0:0:0:0:0:1");
        assertThat(AuthController.addressLiteral("unknown")).isNull();
        assertThat(AuthController.addressLiteral("example.com")).isNull();
        assertThat(AuthController.addressLiteral("999.1.1.1")).isNull();
        assertThat(AuthController.addressLiteral("1.2.3.4; drop table")).isNull();
        assertThat(AuthController.addressLiteral("")).isNull();
        assertThat(AuthController.addressLiteral(null)).isNull();
    }
}
