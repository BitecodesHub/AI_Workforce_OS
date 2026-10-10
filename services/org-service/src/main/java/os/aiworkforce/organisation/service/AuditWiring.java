// @find: audit wiring, audit token source, audit client configuration, analytics token, audit events from org-service
// @what: Configures how org-service obtains a service token to send audit events to analytics-service.
// @flow: Uses InternalTokenProvider; imports AuditClientConfiguration from platform-web.
package os.aiworkforce.organisation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import os.aiworkforce.platform.web.audit.AuditClientConfiguration;
import os.aiworkforce.platform.web.audit.AuditTokenSource;

/**
 * The organisation service records audit events and delivers them through the shared outbox. The
 * relay runs on a timer, outside any request, so its token names the platform; the person an event
 * is about travels inside the event.
 */
@Configuration(proxyBeanMethods = false)
@Import(AuditClientConfiguration.class)
class AuditWiring {

    // @find: audit token for analytics service
    @Bean
    AuditTokenSource auditTokenSource(InternalTokenProvider tokens) {
        return () -> tokens.forService("analytics");
    }
}
