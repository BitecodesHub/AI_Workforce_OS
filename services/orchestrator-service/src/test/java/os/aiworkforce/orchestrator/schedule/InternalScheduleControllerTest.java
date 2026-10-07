package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The notice identity sends when somebody leaves: only ever from a service, never from a person. */
class InternalScheduleControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID LEAVER = UUID.randomUUID();

    private ScheduleService service;
    private InternalScheduleController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScheduleService.class);
        controller = new InternalScheduleController(service);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    /** What identity-service mints for this call: the platform, on behalf of whoever removed them. */
    private static Actor identityToken(String orgId) {
        return new Actor(
                "identity",
                Actor.Kind.SYSTEM,
                orgId,
                null,
                Set.of(),
                0L,
                UUID.randomUUID().toString(),
                null,
                null,
                Map.of());
    }

    @Test
    @DisplayName("a service token pauses the departed person's schedules and reports how many")
    void serviceTokenPauses() {
        Actor identity = identityToken(ORG.toString());
        RequestContext.setActor(identity);
        when(service.pauseForRemovedOwner(ORG, LEAVER, identity)).thenReturn(3);

        InternalScheduleController.OwnerRemovedResponse response =
                controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER));

        assertThat(response.paused()).isEqualTo(3);
        verify(service).pauseForRemovedOwner(ORG, LEAVER, identity);
    }

    @Test
    @DisplayName("a person's token is refused, even an owner's, and nothing is paused")
    void userTokenRefused() {
        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(),
                ORG.toString(),
                "owner",
                Set.of("task:cancel", "member:remove", "run:cancel"),
                0L));

        ApiException refused = catchThrowableOfType(
                () -> controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER)),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a machine key is refused too")
    void apiKeyRefused() {
        RequestContext.setActor(new Actor(
                "key-1",
                Actor.Kind.API_KEY,
                ORG.toString(),
                null,
                Set.of("task:cancel"),
                0L,
                UUID.randomUUID().toString(),
                null,
                null,
                Map.of()));

        ApiException refused = catchThrowableOfType(
                () -> controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER)),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("an agent's token is refused: only the platform's own services may call this")
    void agentTokenRefused() {
        RequestContext.setActor(new Actor(
                "agent-1",
                Actor.Kind.AGENT,
                ORG.toString(),
                null,
                Set.of("task:cancel"),
                0L,
                UUID.randomUUID().toString(),
                null,
                null,
                Map.of()));

        ApiException refused = catchThrowableOfType(
                () -> controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER)),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a service token minted for another workspace cannot pause this one's schedules")
    void otherWorkspaceTokenRefused() {
        RequestContext.setActor(identityToken(UUID.randomUUID().toString()));

        ApiException refused = catchThrowableOfType(
                () -> controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER)),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("no token at all is refused as unauthenticated")
    void noTokenRefused() {
        ApiException refused = catchThrowableOfType(
                () -> controller.ownerRemoved(new InternalScheduleController.OwnerRemovedRequest(ORG, LEAVER)),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_AUTHENTICATED);
        verifyNoInteractions(service);
    }
}
