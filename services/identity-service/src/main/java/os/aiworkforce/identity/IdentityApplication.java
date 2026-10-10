// @find: identity service, startup, main class, spring boot application, IdentityApplication, scheduling, sign in service, users, roles, permissions
// @what: Spring Boot entry point of the identity service.
// @flow: Scans platform-core and platform-web so shared security and audit beans load here.
package os.aiworkforce.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Identity: users, sessions, roles and permissions.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(scanBasePackageClasses = {IdentityApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(basePackageClasses = {IdentityApplication.class, PlatformCore.class, PlatformWeb.class})
@EnableScheduling
public class IdentityApplication {

    // @find: identity service start, run identity application, main
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
