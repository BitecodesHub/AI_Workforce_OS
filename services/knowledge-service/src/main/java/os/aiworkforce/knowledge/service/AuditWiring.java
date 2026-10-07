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

    @Bean
    AuditTokenSource auditTokenSource(InternalTokenProvider tokens) {
        return () -> tokens.forService("analytics");
    }
}
