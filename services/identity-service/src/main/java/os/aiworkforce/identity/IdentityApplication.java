package os.aiworkforce.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Identity: users, sessions, roles and permissions.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(
        scanBasePackageClasses = {IdentityApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(
        basePackageClasses = {IdentityApplication.class, PlatformCore.class, PlatformWeb.class})
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
