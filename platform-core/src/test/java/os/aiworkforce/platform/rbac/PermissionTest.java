package os.aiworkforce.platform.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The permission registry's invariants.
 *
 * <p>The registry is two lists that must never disagree: {@link Permission#ALL}, which the console
 * renders and the seeder writes, and {@link Permission.Codes}, which endpoints reference in their
 * annotations. A constant that has drifted from its permission produces an endpoint requiring a
 * code that nothing grants - an authorisation check nobody can satisfy, which fails as "the button
 * does not work" rather than as anything a developer would recognise.
 */
class PermissionTest {

    @Test
    @DisplayName("every annotation constant resolves to a registered permission")
    void codesMatchRegistry() throws IllegalAccessException {
        List<String> orphans = new ArrayList<>();
        for (Field field : Permission.Codes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String code = (String) field.get(null);
            if (!Permission.isKnown(code)) {
                orphans.add(field.getName() + " = " + code);
            }
        }
        assertThat(orphans)
                .as("Permission.Codes constants with no matching entry in Permission.ALL")
                .isEmpty();
    }

    @Test
    @DisplayName("every registered permission has an annotation constant")
    void registryHasCodes() {
        List<String> declared = new ArrayList<>();
        for (Field field : Permission.Codes.class.getDeclaredFields()) {
            try {
                declared.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        assertThat(declared)
                .as("a permission without a constant cannot be used in @RequiresPermission")
                .containsAll(Permission.ALL.stream().map(Permission::code).toList());
    }

    @Test
    @DisplayName("codes are unique and correctly shaped")
    void codesAreWellFormed() {
        assertThat(Permission.ALL.stream().map(Permission::code).distinct().count())
                .isEqualTo(Permission.ALL.size());

        for (Permission permission : Permission.ALL) {
            assertThat(permission.code()).matches("[a-z_]+:[a-z_]+");
            assertThat(permission.description())
                    .as("%s needs a sentence for the console", permission.code())
                    .isNotBlank()
                    .endsWith(".");
        }
    }

    @Test
    @DisplayName("permissions that can widen authority are marked administrative")
    void authorityWideningPermissionsAreMarked() {
        // These are the codes that let a holder change who can do what. Miss one and a role that
        // looks safe can quietly promote itself, which is the failure mode worth a test of its own.
        List<String> mustBeAdministrative = List.of(
                "role:create", "role:update", "role:delete",
                "member:update", "member:remove", "member:invite",
                "agent:grant_tools", "agent:set_approval_policy",
                "integration:connect", "provider:manage", "settings:update");

        for (String code : mustBeAdministrative) {
            assertThat(Permission.byCode(code).administrative())
                    .as("%s can widen authority and must be marked administrative", code)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an unknown code is rejected rather than silently accepted")
    void unknownCodeIsRejected() {
        assertThat(Permission.isKnown("agent:teleport")).isFalse();
        assertThat(Permission.isKnown(null)).isFalse();
    }
}
