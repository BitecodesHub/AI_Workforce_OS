package os.aiworkforce.identity.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creates one account per role, so the platform can be tried without anybody being set up first.
 *
 * <p>An evaluator who has to invent a workspace, invite four colleagues and assign them roles
 * before they can see what "manager" is unable to do will not do it. Five accounts that already
 * exist turn that from an afternoon into a click, and they make the permission model visible in
 * the way that actually convinces people: by signing in as an employee and finding the Members
 * screen is not there.
 *
 * <p>Three things keep this from being a liability:
 *
 * <ul>
 *   <li>It refuses to run in staging or production, whatever the property says. A demo account
 *       with a published password is a back door, and a flag that can be set by mistake is not
 *       enough protection for one.
 *   <li>It is idempotent. It creates what is missing and never rewrites an existing account, so
 *       a password somebody changed stays changed.
 *   <li>The password is deliberately published, in the sign-in screen and here. A shared secret
 *       everybody knows is being treated as a secret is worse than one nobody pretends about.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "aiwos.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Published on the sign-in screen. Long enough to satisfy the platform's own minimum. */
    public static final String DEMO_PASSWORD = "demo-workspace-2026";

    /** A fixed identifier, so the demo workspace is the same one across restarts and services. */
    public static final UUID DEMO_ORG_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private record DemoAccount(String email, String displayName, String role, String describes) {}

    private static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount(
                    "owner@demo.aiworkforce.os",
                    "Ava Owner",
                    "owner",
                    "Everything, including billing and closing the workspace"),
            new DemoAccount(
                    "admin@demo.aiworkforce.os",
                    "Arjun Admin",
                    "admin",
                    "Manages people, agents, integrations and settings"),
            new DemoAccount(
                    "manager@demo.aiworkforce.os",
                    "Maya Manager",
                    "manager",
                    "Runs agents and approves their actions, but cannot change who may"),
            new DemoAccount(
                    "employee@demo.aiworkforce.os",
                    "Eli Employee",
                    "employee",
                    "Asks questions and hands routine work to agents"),
            new DemoAccount(
                    "viewer@demo.aiworkforce.os",
                    "Vik Viewer",
                    "viewer",
                    "Reads dashboards and traces, changes nothing"));

    private final Users users;
    private final Roles roles;
    private final Memberships memberships;
    private final PasswordService passwords;
    private final PlatformProperties properties;

    public DemoDataSeeder(
            Users users,
            Roles roles,
            Memberships memberships,
            PasswordService passwords,
            PlatformProperties properties) {
        this.users = users;
        this.roles = roles;
        this.memberships = memberships;
        this.passwords = passwords;
        this.properties = properties;
    }

    /*
     * Ordered after PermissionSeeder, which creates the system roles these memberships point at.
     * The annotation belongs on the method: @Order on the class does not order event listeners,
     * and the seeder silently created nothing when it ran first.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    @Transactional
    public void seed() {
        if (properties.environment().isDeployed()) {
            // The property alone is not enough. Accounts whose password is printed on a login
            // screen must not be creatable in a deployed environment by flipping a flag.
            log.info("Demo accounts are not created in {} mode", properties.environment());
            return;
        }

        int created = 0;
        for (DemoAccount account : ACCOUNTS) {
            if (users.existsByEmail(account.email())) {
                continue;
            }
            Role role = roles.findSystemRole(account.role()).orElse(null);
            if (role == null) {
                log.warn("System role '{}' is missing; skipping demo account {}", account.role(), account.email());
                continue;
            }

            User user = new User();
            user.setId(UuidV7.generate());
            user.setEmail(account.email());
            user.setDisplayName(account.displayName());
            user.setPasswordHash(passwords.hash(DEMO_PASSWORD));
            user.setStatus("active");
            // Verified on creation: an evaluator should not have to find a confirmation email
            // that was never sent anywhere.
            user.setEmailVerifiedAt(Instant.now());
            users.save(user);

            Membership membership = new Membership();
            membership.setId(UuidV7.generate());
            membership.setUserId(user.getId());
            membership.setOrgId(DEMO_ORG_ID);
            membership.setRoleId(role.getId());
            membership.setStatus("active");
            membership.setJoinedAt(Instant.now());
            memberships.save(membership);

            created++;
        }

        if (created > 0) {
            log.info("Created {} demo account(s) in workspace {}. Password: {}", created, DEMO_ORG_ID, DEMO_PASSWORD);
        }
    }

    /** What the sign-in screen offers, so the list exists in one place rather than two. */
    public static List<DemoAccountView> published() {
        return ACCOUNTS.stream()
                .map(account -> new DemoAccountView(
                        account.email(), account.displayName(), account.role(), account.describes()))
                .toList();
    }

    public record DemoAccountView(String email, String displayName, String role, String describes) {}
}
