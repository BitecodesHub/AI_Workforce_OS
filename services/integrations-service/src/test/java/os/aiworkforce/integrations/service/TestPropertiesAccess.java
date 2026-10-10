// @find: test properties access, share test platform settings across packages, test helper
// @what: Public helper letting tests in other packages reuse the service test settings.
package os.aiworkforce.integrations.service;

import os.aiworkforce.platform.config.PlatformProperties;

/** Lets tests in other packages use the platform settings the service tests use. */
public final class TestPropertiesAccess {

    private TestPropertiesAccess() {}

    public static PlatformProperties properties() {
        return TestProperties.properties();
    }
}
