// @find: demo workspace, demo data, seed demo organisation, Demo Workspace, startup seeding, demo org id, dev sample data
// @what: Creates the Demo Workspace row on startup in non-deployed environments.
// @flow: Runs on ApplicationReadyEvent; identity-service seeds the matching demo users.
package os.aiworkforce.organisation.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.organisation.domain.Organisation;
import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Creates the demo workspace itself.
 *
 * <p>Identity seeds five demo accounts and orchestrator seeds four demo agents, both against a
 * fixed organisation id - but nothing created the organisation row that id points at. Every
 * feature this service owns for that workspace (credentials, settings, working hours) failed with
 * a foreign-key violation because, as far as this service's own database was concerned, the demo
 * workspace did not exist. Idempotent and local-only, matching the other two seeders.
 */
@Component
@ConditionalOnProperty(name = "aiwos.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Matches identity's and orchestrator's demo workspace id, so all three meet in one place. */
    public static final UUID DEMO_ORG_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final Organisations organisations;
    private final PlatformProperties properties;

    public DemoDataSeeder(Organisations organisations, PlatformProperties properties) {
        this.organisations = organisations;
        this.properties = properties;
    }

    // @find: seed demo workspace on startup, create Demo Workspace
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (properties.environment().isDeployed()) {
            log.info("Demo workspace is not created in {} mode", properties.environment());
            return;
        }
        if (organisations.existsById(DEMO_ORG_ID)) {
            return;
        }

        Organisation organisation = new Organisation();
        organisation.setId(DEMO_ORG_ID);
        organisation.setName("Demo Workspace");
        organisation.setSlug("demo-workspace");
        organisations.save(organisation);

        log.info("Created demo workspace {}", DEMO_ORG_ID);
    }
}
