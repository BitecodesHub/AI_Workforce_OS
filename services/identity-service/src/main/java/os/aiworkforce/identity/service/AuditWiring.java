package os.aiworkforce.identity.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.web.audit.AuditClientConfiguration;
import os.aiworkforce.platform.web.audit.AuditTokenSource;

/**
 * Identity records audit events like every other service, and delivers them like every other
 * service, with one difference: it does not need to ask itself for a token. Identity is where
 * service tokens are signed, so the relay signs its own, through the same method that answers every
 * other service's request for one.
 *
 * <p>The token names the platform, not a person: the relay runs on a timer, outside any request, and
 * the person an event concerns travels inside the event.
 */
@Configuration(proxyBeanMethods = false)
@Import(AuditClientConfiguration.class)
class AuditWiring {

    @Bean
    AuditTokenSource auditTokenSource(TokenService tokens) {
        return () -> tokens.issueInternalToken("analytics", Actor.SYSTEM).token();
    }
}
