package os.aiworkforce.platform.web.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * Turns a verified token into the platform's {@link Actor} and publishes it to the request context.
 *
 * <p>The permission set travels inside the token, so a request costs no database round trip to
 * authorise. That trade has one consequence worth stating: a permission taken away, a role
 * changed or a membership ended is not felt until the token is replaced. Nothing here checks a
 * token against the role's current state - the {@code pv} claim carries the role's permission
 * version, but no service compares it yet. The change takes effect at the next refresh, when
 * identity reads the role and membership afresh, which is within the access-token lifetime
 * ({@code aiwos.security.access-token-ttl}, five minutes).
 */
@Component
public class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String CLAIM_ORG = "org";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_PERMISSION_VERSION = "pv";
    public static final String CLAIM_KIND = "kind";
    public static final String CLAIM_ON_BEHALF_OF = "obo";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_AGENT = "agent";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> permissions = readPermissions(jwt);
        Actor actor = new Actor(
                jwt.getSubject(),
                readKind(jwt),
                jwt.getClaimAsString(CLAIM_ORG),
                jwt.getClaimAsString(CLAIM_ROLE),
                permissions,
                readVersion(jwt),
                jwt.getClaimAsString(CLAIM_ON_BEHALF_OF),
                jwt.getClaimAsString(CLAIM_SESSION),
                jwt.getClaimAsString(CLAIM_AGENT),
                Map.of());

        RequestContext.setActor(actor);
        MDC.put("actorId", actor.id());
        if (actor.orgId() != null) {
            MDC.put("orgId", actor.orgId());
        }

        return new JwtAuthenticationToken(jwt, toAuthorities(permissions), actor.id());
    }

    private static Actor.Kind readKind(Jwt jwt) {
        String kind = jwt.getClaimAsString(CLAIM_KIND);
        if (kind == null) {
            return Actor.Kind.USER;
        }
        return switch (kind) {
            case "api_key" -> Actor.Kind.API_KEY;
            case "agent" -> Actor.Kind.AGENT;
            case "system" -> Actor.Kind.SYSTEM;
            default -> Actor.Kind.USER;
        };
    }

    private static Set<String> readPermissions(Jwt jwt) {
        Object raw = jwt.getClaim(CLAIM_PERMISSIONS);
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        if (raw instanceof String joined && !joined.isBlank()) {
            // Space-delimited is the OAuth convention and keeps a large permission set compact.
            return Set.of(joined.trim().split("\\s+"));
        }
        return Set.of();
    }

    private static long readVersion(Jwt jwt) {
        Object raw = jwt.getClaim(CLAIM_PERMISSION_VERSION);
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Exposes permissions to Spring Security as authorities too.
     *
     * <p>The platform's own {@code @RequiresPermission} is the primary check, but publishing the
     * same set as authorities means {@code @PreAuthorize} and the filter chain agree with it
     * rather than contradicting it.
     */
    private static List<GrantedAuthority> toAuthorities(Set<String> permissions) {
        return permissions.stream()
                .map(code -> (GrantedAuthority) new SimpleGrantedAuthority("PERM_" + code))
                .toList();
    }
}
