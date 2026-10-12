// @find: tests for identity permissions cache, identity session liveness cache, batch permissions, failure pause, IdentityLookupsTest
// @what: Unit tests for IdentityMemberPermissions and IdentitySessionLiveness: one call per batch, answers cached for a while, a failure leaves people out and pauses further calls.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the push code asks identity, and how often. */
class IdentityLookupsTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private static List<UUID> people(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(new UUID(0x0000000000007000L, 0x0000000000100000L + i));
        }
        return ids;
    }

    @Test
    @DisplayName("permissions for many people are asked in batches, cached, and a non-member holds nothing")
    void permissionsAreBatchedAndCached() {
        VapidKeyStoreTest.MutableClock clock = new VapidKeyStoreTest.MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
        List<UUID> everyone = people(250);
        UUID former = everyone.get(1);
        List<List<UUID>> asked = new ArrayList<>();
        IdentityMemberPermissions permissions = new IdentityMemberPermissions((orgId, userIds) -> {
            asked.add(List.copyOf(userIds));
            Map<UUID, List<String>> codes = new HashMap<>();
            userIds.stream().filter(id -> !id.equals(former)).forEach(id -> codes.put(id, List.of("approval:decide")));
            return new IdentityMemberPermissions.BatchAnswer(codes);
        }, clock);

        Map<UUID, Set<String>> held = permissions.permissionsOf(ORG, everyone);

        assertThat(asked).extracting(List::size).containsExactly(IdentityMemberPermissions.BATCH, 50);
        assertThat(held).hasSize(250);
        assertThat(held.get(former)).isEmpty();
        assertThat(held.get(everyone.get(0))).containsExactly("approval:decide");

        // Within the cache's time nothing more is asked; one person alone reads the same cache.
        permissions.permissionsOf(ORG, everyone);
        assertThat(permissions.permissionsOf(ORG, everyone.get(0))).contains(Set.of("approval:decide"));
        assertThat(asked).hasSize(2);

        clock.now = clock.now.plusSeconds(IdentityMemberPermissions.TTL_SECONDS + 1);
        permissions.permissionsOf(ORG, everyone.subList(0, 3));
        assertThat(asked).hasSize(3);
    }

    @Test
    @DisplayName("when identity fails, those people are left out and identity is not asked again for a few seconds")
    void permissionFailurePauses() {
        VapidKeyStoreTest.MutableClock clock = new VapidKeyStoreTest.MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
        int[] calls = {0};
        boolean[] down = {true};
        IdentityMemberPermissions permissions = new IdentityMemberPermissions((orgId, userIds) -> {
            calls[0]++;
            if (down[0]) {
                throw new IllegalStateException("identity is down");
            }
            Map<UUID, List<String>> codes = new HashMap<>();
            userIds.forEach(id -> codes.put(id, List.of("approval:decide")));
            return new IdentityMemberPermissions.BatchAnswer(codes);
        }, clock);
        List<UUID> two = people(2);

        assertThat(permissions.permissionsOf(ORG, two)).isEmpty();
        assertThat(permissions.permissionsOf(ORG, two.get(0))).isEmpty();
        for (int i = 0; i < 30; i++) {
            permissions.permissionsOf(ORG, two);
        }
        assertThat(calls[0]).isEqualTo(1);

        down[0] = false;
        clock.now = clock.now.plusSeconds(IdentityMemberPermissions.FAILURE_PAUSE_SECONDS + 1);
        assertThat(permissions.permissionsOf(ORG, two)).hasSize(2);
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("live sessions are asked about once per batch of unknown ones, cached, and a failure pauses further calls")
    void sessionLivenessIsCached() {
        VapidKeyStoreTest.MutableClock clock = new VapidKeyStoreTest.MutableClock(Instant.parse("2026-10-12T09:00:00Z"));
        List<UUID> sessions = people(3);
        UUID ended = sessions.get(2);
        List<List<UUID>> asked = new ArrayList<>();
        boolean[] down = {false};
        IdentitySessionLiveness liveness = new IdentitySessionLiveness(ids -> {
            asked.add(List.copyOf(ids));
            if (down[0]) {
                throw new IllegalStateException("identity is down");
            }
            return new IdentitySessionLiveness.Answer(ids.stream().filter(id -> !id.equals(ended)).toList());
        }, clock);

        assertThat(liveness.live(sessions)).contains(Set.of(sessions.get(0), sessions.get(1)));
        assertThat(liveness.live(sessions)).contains(Set.of(sessions.get(0), sessions.get(1)));
        assertThat(asked).hasSize(1);

        // Only a session not seen yet is asked about.
        UUID fresh = UUID.randomUUID();
        liveness.live(List.of(sessions.get(0), fresh));
        assertThat(asked.get(1)).containsExactly(fresh);

        // An answer that has aged is asked again; a failure says "unknown" and pauses.
        clock.now = clock.now.plusSeconds(IdentitySessionLiveness.TTL_SECONDS + 1);
        down[0] = true;
        assertThat(liveness.live(sessions)).isEqualTo(Optional.empty());
        assertThat(liveness.live(sessions)).isEqualTo(Optional.empty());
        assertThat(asked).hasSize(3);
    }
}
