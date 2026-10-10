// @find: analytics service, application entry, spring boot main, audit log service, analytics app
// @what: Spring Boot entry point of analytics-service.
package os.aiworkforce.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Analytics: read models, dashboards and the audit projection.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(scanBasePackageClasses = {AnalyticsApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(basePackageClasses = {AnalyticsApplication.class, PlatformCore.class, PlatformWeb.class})
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
