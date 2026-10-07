package os.aiworkforce.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every permission code is either checked somewhere or openly marked planned.
 *
 * <p>The console offers every code that is not planned as a checkbox. A code nobody enforces but
 * nobody marked planned is a checkbox that grants nothing, which a buyer testing the role builder
 * finds in minutes. Adding a code to {@link Permission#ALL} therefore means deciding, here, which
 * of the two it is.
 */
class PermissionEnforcementTest {

    /**
     * Codes an endpoint or a service check enforces.
     *
     * <p>Five of these are listed ahead of their check, because the work that adds it is already
     * scheduled and hiding them in between would only make roles flicker: {@code memory:read}
     * (the memory API's permission gates), {@code budget:read} and {@code budget:manage} (the
     * budget API), and {@code agent:delete} and {@code agent:set_approval_policy} (agent
     * management). Move a code to {@link #PLANNED} if that work is dropped.
     */
    private static final Set<String> ENFORCED = Set.of(
            "workspace:read",
            "workspace:update",
            "member:read",
            "member:invite",
            "member:update",
            "member:remove",
            "role:read",
            "role:create",
            "role:update",
            "role:delete",
            "agent:read",
            "agent:create",
            "agent:update",
            "agent:delete",
            "agent:run",
            "agent:grant_tools",
            "agent:set_model_policy",
            "agent:set_approval_policy",
            "task:read",
            "task:create",
            "task:cancel",
            "run:read",
            "run:cancel",
            "chat:use",
            "chat:read_all",
            "approval:read",
            "approval:decide",
            "knowledge:read",
            "knowledge:query",
            "knowledge:source_manage",
            "integration:read",
            "integration:connect",
            "integration:disconnect",
            "provider:read",
            "provider:manage",
            "budget:read",
            "budget:manage",
            "audit:read",
            "analytics:read",
            "memory:read");

    /** Codes reserved for features no service checks yet. Each was searched for and found unused. */
    private static final Set<String> PLANNED = Set.of(
            "workspace:delete",
            "api_key:read",
            "api_key:manage",
            "run:replay",
            "settings:read",
            "settings:update",
            "memory:purge");

    @Test
    @DisplayName("every registered code is listed as enforced or planned, never both and never neither")
    void everyCodeIsClassified() {
        List<String> unclassified = new ArrayList<>();
        for (Permission permission : Permission.ALL) {
            boolean enforced = ENFORCED.contains(permission.code());
            boolean planned = PLANNED.contains(permission.code());
            if (enforced == planned) {
                unclassified.add(permission.code());
            }
        }
        assertThat(unclassified)
                .as("add each new code to ENFORCED (and check it somewhere) or to PLANNED (and mark it planned)")
                .isEmpty();
    }

    @Test
    @DisplayName("the lists name only codes the registry knows")
    void listsHoldOnlyRegisteredCodes() {
        for (String code : ENFORCED) {
            assertThat(Permission.isKnown(code)).as("%s is not registered", code).isTrue();
        }
        for (String code : PLANNED) {
            assertThat(Permission.isKnown(code)).as("%s is not registered", code).isTrue();
        }
    }

    @Test
    @DisplayName("the planned flag on each permission agrees with the list")
    void plannedFlagMatchesTheList() {
        Set<String> flagged = Permission.ALL.stream()
                .filter(Permission::planned)
                .map(Permission::code)
                .collect(Collectors.toSet());
        assertThat(flagged).isEqualTo(PLANNED);
    }

    @Test
    @DisplayName("planned codes stay known, so stored roles holding them remain valid, but are not offered")
    void plannedCodesStayKnownButUnoffered() {
        for (String code : PLANNED) {
            assertThat(Permission.isKnown(code)).isTrue();
            assertThat(Permission.isPlanned(code)).isTrue();
        }
        assertThat(Permission.available()).noneMatch(Permission::planned);
        assertThat(Permission.available()).hasSize(Permission.ALL.size() - PLANNED.size());
        assertThat(Permission.isPlanned("agent:teleport")).isFalse();
        assertThat(Permission.isPlanned(null)).isFalse();
    }
}
