package os.aiworkforce.platform.web.persistence;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * Switches on the auditing that {@code BaseEntity} declares.
 *
 * <p>{@link CreatedDate} and {@link CreatedBy} are inert without this: the listener is registered
 * on the entity but nothing populates it, so every insert writes nulls and any column declared
 * {@code NOT NULL} rejects the row. That failure looks like a schema problem rather than a
 * missing configuration, which is why it is worth stating here.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "platformAuditorAware")
public class JpaAuditingConfig {

    /**
     * Who to record as having made a change.
     *
     * <p>Returns the person behind the work rather than whatever is executing it. When an agent
     * writes a row, {@code created_by} names the human whose request started the run - an audit
     * column reading "agent-7" answers nothing anybody actually asks of it.
     *
     * <p>Background work with no actor is recorded as {@code system}, which is honest and
     * distinguishable from a person.
     */
    @Bean
    public AuditorAware<String> platformAuditorAware() {
        return () -> RequestContext.actor()
                .map(actor -> {
                    String human = actor.humanId();
                    return human != null ? human : actor.id();
                })
                .or(() -> Optional.of(Actor.SYSTEM.id()));
    }
}
