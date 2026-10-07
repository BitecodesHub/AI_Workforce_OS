package os.aiworkforce.platform.web.audit;

import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The audit client and its relay, for a service that imports it.
 *
 * <p>Deliberately not annotated as a component. Every service scans this package for the platform's
 * shared beans, and a service that does not record audit events - or that already has its own
 * client, as the orchestrator does - must not get an outbox it has no table for. A service opts in
 * with {@code @Import(AuditClientConfiguration.class)} and supplies one {@link AuditTokenSource};
 * {@code aiwos.audit.enabled=false} then switches recording off without removing the wiring.
 *
 * <p>The service must also create the {@code audit_outbox} table in its own migrations.
 */
@Import(AuditClientConfiguration.Wiring.class)
public final class AuditClientConfiguration {

    private AuditClientConfiguration() {}

    /*
     * Deliberately not annotated @Configuration: a nested configuration class is found by the
     * component scan every service runs over this package, which would give a service that does not
     * record audit events the outbox and relay (and a missing-token-source failure at startup). As a
     * plain class it is picked up only by the @Import above, when a service asks for it.
     */
    @EnableScheduling
    @EnableConfigurationProperties(AuditProperties.class)
    static class Wiring {

        @Bean
        AuditOutbox auditOutbox(JdbcTemplate jdbc) {
            return new JdbcAuditOutbox(jdbc);
        }

        @Bean
        AuditSender auditSender(
                WebClient.Builder builder,
                PlatformProperties platform,
                AuditTokenSource tokens,
                ObjectMapper json,
                AuditProperties properties) {
            WebClient client = builder.clone().baseUrl(platform.services().analytics()).build();
            return new HttpAuditSender(client, tokens, json, properties.sendTimeout());
        }

        @Bean(destroyMethod = "shutdown")
        AuditOutboxRelay auditOutboxRelay(AuditOutbox outbox, AuditSender sender, AuditProperties properties) {
            return new AuditOutboxRelay(outbox, sender, properties);
        }

        @Bean
        AuditClient auditClient(
                AuditOutbox outbox, AuditOutboxRelay relay, ObjectMapper json, AuditProperties properties) {
            return new AuditClient(outbox, relay, json, properties);
        }

        /** How many events are waiting. Above zero for long means analytics-service is not taking them. */
        @Bean
        Object auditOutboxGauge(AuditOutbox outbox, ObjectProvider<MeterRegistry> registry) {
            AtomicLong holder = new AtomicLong();
            registry.ifAvailable(meters -> Gauge.builder("aiwos.audit.outbox.pending", () -> {
                        try {
                            holder.set(outbox.pending());
                        } catch (RuntimeException unavailable) {
                            // The last known value stands until the database answers again.
                        }
                        return holder.get();
                    })
                    .description("Audit events recorded here and not yet accepted by analytics-service")
                    .register(meters));
            return holder;
        }
    }
}
