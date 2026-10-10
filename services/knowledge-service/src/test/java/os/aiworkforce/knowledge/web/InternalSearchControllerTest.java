// @find: tests for agent knowledge search, internal search, search as a person, restricted sources, person permissions, POST /internal/knowledge/search, knowledge base
// @what: Checks who an agent's document search is made for and what that person may read.
package os.aiworkforce.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.knowledge.service.RetrievalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Who an agent's document search is made for, and what that person may read. The retrieval and the
 * identity lookup are stubs: what is checked is which questions the controller asks of them, and
 * that a search is never made when the answer is no.
 */
class InternalSearchControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final String QUERY = "refund policy";

    private RetrievalService retrieval;
    private InternalSearchController.PersonPermissions people;
    private InternalSearchController controller;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        people = mock(InternalSearchController.PersonPermissions.class);
        controller = new InternalSearchController(retrieval, people);
        when(retrieval.retrieve(any(), anyString(), anyInt(), any(), anyBoolean(), any()))
                .thenReturn(new RetrievalService.Retrieval(List.of(passage()), false));
    }

    private static RetrievalService.Passage passage() {
        return new RetrievalService.Passage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Refund policy",
                null,
                2,
                "Refunds",
                "Refunds are issued within 5 business days.",
                0.9);
    }

    /** The token an agent's run presents: a service token, naming the workspace and the person. */
    private static Actor serviceFor(UUID person) {
        return new Actor(
                person == null ? "system" : person.toString(),
                Actor.Kind.SYSTEM,
                ORG.toString(),
                null,
                Set.of(),
                0L,
                person == null ? null : person.toString(),
                null,
                null,
                Map.of());
    }

    private KnowledgeController.SearchResponse search(Actor actor) {
        return RequestContext.as(actor, () -> controller.search(request(), null));
    }

    private static InternalSearchController.InternalSearchRequest request() {
        return new InternalSearchController.InternalSearchRequest(QUERY, null, null, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("a person's own token is refused, whatever it carries: they have the public search")
    void aPersonsOwnTokenIsRefused() {
        Actor user = Actor.user(
                PERSON.toString(),
                ORG.toString(),
                null,
                Set.of(Permission.Codes.KNOWLEDGE_QUERY, Permission.Codes.KNOWLEDGE_SOURCE_MANAGE),
                1L);

        assertThatThrownBy(() -> search(user))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));

        verifyNoInteractions(retrieval, people);
    }

    @Test
    @DisplayName("work with nobody behind it is refused: the platform's own actor never searches documents")
    void systemActorGetsNoDocuments() {
        assertThatThrownBy(() -> search(serviceFor(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                    assertThat(e.getMessage()).isEqualTo("Documents are not available for this run.");
                });

        verifyNoInteractions(retrieval, people);
    }

    @Test
    @DisplayName("the person must hold knowledge:query, checked against their membership and not the token")
    void personWithoutKnowledgeQueryIsRefused() {
        when(people.permissionsOf(ORG, PERSON)).thenReturn(Set.of("task:create"));

        assertThatThrownBy(() -> search(serviceFor(PERSON)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
                    assertThat(e.details()).containsEntry("requiredPermission", Permission.Codes.KNOWLEDGE_QUERY);
                });

        verify(retrieval, never()).retrieve(any(), anyString(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("someone who is no longer a member holds nothing, so nothing is searched")
    void formerMemberIsRefused() {
        when(people.permissionsOf(ORG, PERSON)).thenReturn(Set.of());

        assertThatThrownBy(() -> search(serviceFor(PERSON))).isInstanceOf(ApiException.class);

        verify(retrieval, never()).retrieve(any(), anyString(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("restricted sources are left out unless the person also holds knowledge:source_manage")
    void restrictedSourcesAreExcludedForOrdinaryReaders() {
        when(people.permissionsOf(ORG, PERSON)).thenReturn(Set.of(Permission.Codes.KNOWLEDGE_QUERY));

        KnowledgeController.SearchResponse response = search(serviceFor(PERSON));

        assertThat(response.grounded()).isTrue();
        assertThat(response.passages()).hasSize(1);
        verify(retrieval).retrieve(eq(ORG), eq(QUERY), eq(InternalSearchController.DEFAULT_LIMIT), isNull(), eq(false), any());
    }

    @Test
    @DisplayName("a person who manages knowledge searches restricted sources too")
    void managersSearchRestrictedSources() {
        when(people.permissionsOf(ORG, PERSON))
                .thenReturn(Set.of(Permission.Codes.KNOWLEDGE_QUERY, Permission.Codes.KNOWLEDGE_SOURCE_MANAGE));

        search(serviceFor(PERSON));

        verify(retrieval).retrieve(eq(ORG), eq(QUERY), anyInt(), isNull(), eq(true), any());
    }

    @Test
    @DisplayName("permissions in the token are ignored: a service token that claimed them still has to pass the lookup")
    void tokenPermissionsAreNotTrusted() {
        Actor claimsEverything = new Actor(
                PERSON.toString(),
                Actor.Kind.SYSTEM,
                ORG.toString(),
                null,
                Set.of(Permission.Codes.KNOWLEDGE_QUERY, Permission.Codes.KNOWLEDGE_SOURCE_MANAGE),
                0L,
                PERSON.toString(),
                null,
                null,
                Map.of());
        when(people.permissionsOf(ORG, PERSON)).thenReturn(Set.of(Permission.Codes.KNOWLEDGE_QUERY));

        search(claimsEverything);

        verify(retrieval).retrieve(eq(ORG), eq(QUERY), anyInt(), isNull(), eq(false), any());
    }

    @Test
    @DisplayName("when identity cannot be asked the search is refused as unavailable, never widened")
    void identityDownFailsClosed() {
        when(people.permissionsOf(ORG, PERSON))
                .thenThrow(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not check who this is for."));

        assertThatThrownBy(() -> search(serviceFor(PERSON)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

        verify(retrieval, never()).retrieve(any(), anyString(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a header naming another workspace than the token's is refused")
    void otherWorkspaceHeaderIsRefused() {
        assertThatThrownBy(() -> RequestContext.as(serviceFor(PERSON), () -> controller.search(request(), UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ORGANISATION_MISMATCH));

        verifyNoInteractions(people);
        verify(retrieval, never()).retrieve(any(), anyString(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("the status says how many sources hold passages, to a service and not to a person")
    void statusCountsIndexedSources() {
        when(retrieval.indexedSources(ORG, null)).thenReturn(2);

        assertThat(RequestContext.as(serviceFor(PERSON), () -> controller.status(null, null)).indexedSources())
                .isEqualTo(2);

        Actor user = Actor.user(PERSON.toString(), ORG.toString(), null, Set.of(), 1L);
        assertThatThrownBy(() -> RequestContext.as(user, () -> controller.status(null, null)))
                .isInstanceOf(ApiException.class);
    }
}
