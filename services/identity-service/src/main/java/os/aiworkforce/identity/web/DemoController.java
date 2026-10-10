// @find: demo accounts, demo login buttons, GET /api/auth/demo-accounts, sign in screen, DemoController, try demo
// @what: Lists the demo accounts and shared password for the sign-in screen in local and test only.
// @flow: Reads DemoDataSeeder.published.
package os.aiworkforce.identity.web;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.service.DemoDataSeeder;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Tells the sign-in screen which demo accounts exist.
 *
 * <p>The screen asks rather than hard-coding the list, so the accounts offered are always the
 * ones that were actually created. A login button for an account that does not exist is a worse
 * first impression than no button at all.
 *
 * <p>The whole controller disappears outside local and test, so a deployed environment does not
 * advertise accounts it has deliberately not created.
 */
@RestController
@RequestMapping("/api/auth/demo-accounts")
@Tag(name = "Authentication")
@ConditionalOnProperty(name = "aiwos.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoController {

    private final PlatformProperties properties;

    public DemoController(PlatformProperties properties) {
        this.properties = properties;
    }

    /**
     * @param accounts one per role, with a sentence on what that role can and cannot do
     * @param password published deliberately: pretending a shared demo password is secret is
     *     worse than stating it
     */
    public record DemoAccounts(List<DemoDataSeeder.DemoAccountView> accounts, String password) {}

    // @find: list demo accounts, GET /api/auth/demo-accounts
    @GetMapping
    @Operation(summary = "Demo accounts available in this environment")
    public DemoAccounts list() {
        if (properties.environment().isDeployed()) {
            return new DemoAccounts(List.of(), null);
        }
        return new DemoAccounts(DemoDataSeeder.published(), DemoDataSeeder.DEMO_PASSWORD);
    }
}
