package os.aiworkforce.organisation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Organisations: workspaces, membership and governance policy.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(
        scanBasePackageClasses = {OrganisationApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(
        basePackageClasses = {OrganisationApplication.class, PlatformCore.class, PlatformWeb.class})
public class OrganisationApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrganisationApplication.class, args);
    }
}
