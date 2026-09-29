package os.aiworkforce.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import os.aiworkforce.platform.PlatformCore;
import os.aiworkforce.platform.web.PlatformWeb;

/**
 * Memory: working, episodic and semantic memory shared across agents.
 *
 * <p>An independently deployable Spring Boot application. It owns its own database schema and
 * its own migration history, and it never reaches into another service's tables: everything it
 * needs from a sibling arrives over that sibling's published API or over an event.
 */
@SpringBootApplication(scanBasePackageClasses = {MemoryApplication.class, PlatformCore.class, PlatformWeb.class})
@ConfigurationPropertiesScan(basePackageClasses = {MemoryApplication.class, PlatformCore.class, PlatformWeb.class})
public class MemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryApplication.class, args);
    }
}
