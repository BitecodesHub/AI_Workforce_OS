// @find: tests for team room controller, run:read required, permission denied, invalid cursor, both cursors refused, limit clamped, no-store, TeamRoomControllerTest, TeamRoomController
// @what: Tests for TeamRoomController: run:read is enforced on both endpoints, cursors and limits are checked, and answers are never cached.
// @flow: Exercises TeamRoomController through the platform PermissionInterceptor and directly
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.FeedPage;
import os.aiworkforce.orchestrator.teamroom.TeamRoomViews.TeamRoom;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Both team room reads need run:read, enforced by the same interceptor every endpoint goes
 * through; a role without it is refused before the service is asked anything. A cursor the service
 * did not write, or both cursors at once, is a validation error, and the page size stays in range.
 */
class TeamRoomControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private TeamRoomService service;
    private TeamRoomController controller;

    @BeforeEach
    void setUp() {
        service = mock(TeamRoomService.class);
        controller = new TeamRoomController(service);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static Actor signedInWith(String... permissions) {
        Actor actor = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(permissions), 0L);
        RequestContext.setActor(actor);
        return actor;
    }

    private static FeedPage emptyPage() {
        return new FeedPage(Instant.now(), List.of(), "cursor", null, false, false);
    }

    // @find: test both endpoints need run:read, team room controller
    @Test
    @DisplayName("both endpoints are annotated with run:read")
    void bothEndpointsNeedRunRead() throws Exception {
        assertThat(TeamRoomController.class
                        .getMethod("presence")
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.RUN_READ);
        assertThat(TeamRoomController.class
                        .getMethod("feed", String.class, String.class, int.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.RUN_READ);
    }

    // @find: test a role without run:read is refused, team room controller
    @Test
    @DisplayName("a role without run:read is refused by the permission interceptor, for the presence and the feed")
    void aRoleWithoutRunReadIsRefused() throws Exception {
        signedInWith("chat:use", "agent:read");
        PermissionInterceptor interceptor = new PermissionInterceptor();
        for (Method method : List.of(
                TeamRoomController.class.getMethod("presence"),
                TeamRoomController.class.getMethod("feed", String.class, String.class, int.class))) {
            ApiException refused = catchThrowableOfType(
                    () -> interceptor.preHandle(
                            new MockHttpServletRequest(),
                            new MockHttpServletResponse(),
                            new HandlerMethod(controller, method)),
                    ApiException.class);
            assertThat(refused).as(method.getName()).isNotNull();
            assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(service);
    }

    // @find: test a role with run:read is let through, team room controller
    @Test
    @DisplayName("a role with run:read is let through, and the presence is read for its own workspace and person")
    void aRoleWithRunReadIsLetThrough() throws Exception {
        Actor actor = signedInWith("run:read");
        PermissionInterceptor interceptor = new PermissionInterceptor();
        HandlerMethod handler = new HandlerMethod(controller, TeamRoomController.class.getMethod("presence"));
        TeamRoom room = new TeamRoom(Instant.now(), List.of());
        when(service.presence(ORG, actor)).thenReturn(room);

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isTrue();
        ResponseEntity<TeamRoom> response = controller.presence();

        assertThat(response.getBody()).isSameAs(room);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    // @find: test a cursor it did not write is a validation error, team room controller
    @Test
    @DisplayName("a cursor the service did not write is a validation error naming the parameter")
    void aForeignCursorIsAValidationError() {
        signedInWith("run:read");

        ApiException thrown = catchThrowableOfType(() -> controller.feed("not-a-cursor", null, 50), ApiException.class);

        assertThat(thrown.details()).containsEntry("field", "after");
        verify(service, never()).feed(any(), any(), any(), any(), anyInt());
    }

    // @find: test both cursors at once are refused, team room controller
    @Test
    @DisplayName("after and before together are refused")
    void bothCursorsAreRefused() {
        signedInWith("run:read");
        String cursor = new FeedCursor(Instant.parse("2026-10-12T09:00:00Z"), "g:1").encode();

        ApiException thrown = catchThrowableOfType(() -> controller.feed(cursor, cursor, 50), ApiException.class);

        assertThat(thrown.details()).containsEntry("field", "before");
        verify(service, never()).feed(any(), any(), any(), any(), anyInt());
    }

    // @find: test the page size stays in range, team room controller
    @Test
    @DisplayName("the page size is held between 1 and 100, and the decoded cursor is passed on")
    void thePageSizeStaysInRange() {
        Actor actor = signedInWith("run:read");
        FeedCursor after = new FeedCursor(Instant.parse("2026-10-12T09:00:00Z"), "g:1");
        when(service.feed(eq(ORG), eq(actor), any(), any(), anyInt())).thenReturn(emptyPage());

        controller.feed(null, null, 0);
        verify(service).feed(ORG, actor, null, null, 1);

        ResponseEntity<FeedPage> response = controller.feed(after.encode(), null, 5_000);
        verify(service)
                .feed(eq(ORG), eq(actor), eq(FeedCursor.Poll.at(after)), isNull(), eq(TeamRoomService.MAX_LIMIT));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");

        // A poll position with the floor a backlog must be read again from passes through whole.
        FeedCursor floor = new FeedCursor(Instant.parse("2026-10-12T08:59:40Z"), "");
        controller.feed(new FeedCursor.Poll(after, floor).encode(), null, 50);
        verify(service).feed(eq(ORG), eq(actor), eq(new FeedCursor.Poll(after, floor)), isNull(), eq(50));
    }
}
