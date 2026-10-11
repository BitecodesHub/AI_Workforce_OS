// @find: org-service startup, organisation application, main class, Spring Boot entry point, workspaces service, invitations service
// @what: Spring Boot entry point for org-service.
// @flow: Scans platform-core and platform-web beans; starts the web controllers in web/.
package os.aiworkforce.organisation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Organisations: workspaces, membership and governance policy.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(scanBasePackageClasses = {OrganisationApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(
        basePackageClasses = {OrganisationApplication.class, PlatformCore.class, PlatformWeb.class})
public class OrganisationApplication {

    /**
     * Starts the application. Component scanning covers this service's own package, the shared core
     * module and the shared web module; configuration properties are scanned from the same three
     * places. Run by the JVM at launch; startup fails if the database or a required setting is missing.
     */
    public static void main(String[] args) {
        SpringApplication.run(OrganisationApplication.class, args);
    }
}
