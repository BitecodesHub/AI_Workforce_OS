// @find: audit log, audit events, audit outbox, knowledge audit, analytics-service delivery, AuditTokenSource, knowledge changes recorded
// @what: Wires the shared audit outbox relay so knowledge changes are delivered to analytics-service.
// @flow: Uses InternalTokenProvider to get a service token for analytics.
package os.aiworkforce.knowledge.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import os.aiworkforce.platform.web.audit.AuditClientConfiguration;
import os.aiworkforce.platform.web.audit.AuditTokenSource;

/**
 * The knowledge service records audit events and delivers them through the shared outbox. The relay
 * runs on a timer, outside any request, so its token names the platform; the person an event is
 * about travels inside the event.
 */
@Configuration(proxyBeanMethods = false)
@Import(AuditClientConfiguration.class)
class AuditWiring {

    /**
     * Supplies the token the audit relay presents to analytics-service.
     *
     * <p>Each call asks {@link InternalTokenProvider#forService} for a token for {@code analytics}. The relay
     * runs on a timer outside any request, so no person is in the context and the token names the platform
     * as the actor. The provider caches tokens, so most calls make no network request.
     *
     * @param tokens the source of service tokens
     * @return a token source; a failure of the identity service surfaces as an exception from the call
     */
    @Bean
    AuditTokenSource auditTokenSource(InternalTokenProvider tokens) {
        return () -> tokens.forService("analytics");
    }
}
