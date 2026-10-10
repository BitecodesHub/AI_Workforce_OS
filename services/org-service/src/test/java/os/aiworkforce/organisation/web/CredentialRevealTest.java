// @find: tests for credential reveal, internal credentials endpoint, service token only, person token refused, workspace mismatch, GET /internal/credentials/{ref}
// @what: Tests who may call the internal credential reveal endpoint.
// @flow: Exercises CredentialController.reveal.
package os.aiworkforce.organisation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.organisation.service.CredentialService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * A decrypted credential leaves only for a service token minted for the workspace it names.
 *
 * <p>The workspace header is the caller's say-so; the token's own workspace is what identity
 * vouched for. The two must agree, or one service token could be replayed with a different header
 * to read every workspace's keys.
 */
class CredentialRevealTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private CredentialService credentials;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialService.class);
        when(credentials.reveal(ORG_A, "openai")).thenReturn(Optional.of("sk-a"));
        when(credentials.reveal(ORG_B, "openai")).thenReturn(Optional.of("sk-b"));
        mvc = MockMvcBuilders.standaloneSetup(new CredentialController(credentials))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static Actor service(UUID org) {
        return new Actor(
                "system",
                Actor.Kind.SYSTEM,
                org == null ? null : org.toString(),
                null,
                Set.of(),
                0L,
                null,
                null,
                null,
                Map.of());
    }

    @Test
    @DisplayName("a service token for the named workspace receives the value")
    void sameWorkspace() throws Exception {
        RequestContext.setActor(service(ORG_A));

        mvc.perform(get("/internal/credentials/{ref}", "openai").header("X-Workspace-Id", ORG_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("sk-a"));
    }

    @Test
    @DisplayName("a service token for workspace A naming workspace B is refused before anything is decrypted")
    void otherWorkspace() throws Exception {
        RequestContext.setActor(service(ORG_A));

        mvc.perform(get("/internal/credentials/{ref}", "openai").header("X-Workspace-Id", ORG_B.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organisation_mismatch"));

        verify(credentials, never()).reveal(any(), anyString());
    }

    @Test
    @DisplayName("a service token that names no workspace is refused")
    void noWorkspace() throws Exception {
        RequestContext.setActor(service(null));

        mvc.perform(get("/internal/credentials/{ref}", "openai").header("X-Workspace-Id", ORG_A.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organisation_mismatch"));

        verify(credentials, never()).reveal(any(), anyString());
    }

    @Test
    @DisplayName("a person's token is refused even with provider permissions in the same workspace")
    void personRefused() throws Exception {
        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(),
                ORG_A.toString(),
                "owner",
                Set.of(Permission.Codes.PROVIDER_READ, Permission.Codes.PROVIDER_MANAGE),
                0L));

        mvc.perform(get("/internal/credentials/{ref}", "openai").header("X-Workspace-Id", ORG_A.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        verify(credentials, never()).reveal(any(), anyString());
    }

    @Test
    @DisplayName("a machine key is refused even for its own workspace")
    void apiKeyRefused() throws Exception {
        RequestContext.setActor(new Actor(
                "key-1", Actor.Kind.API_KEY, ORG_A.toString(), null, Set.of(), 0L, "person", null, null, Map.of()));

        mvc.perform(get("/internal/credentials/{ref}", "openai").header("X-Workspace-Id", ORG_A.toString()))
                .andExpect(status().isForbidden());

        verify(credentials, never()).reveal(any(), anyString());
    }
}
