// @find: knowledge base, knowledge, documents, sources, agent search knowledge, search tool, internal search, POST /internal/knowledge/search, GET /internal/knowledge/status, searches as the person, restricted sources, person permissions, indexed sources count, InternalSearchController
// @what: Internal endpoints the AI employees use to search the knowledge base as a given person, honouring restricted and agent-owned sources.
// @flow: Called by orchestrator-service tool calls; uses RetrievalService and a permission lookup against identity-service.
package os.aiworkforce.knowledge.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.knowledge.service.InternalTokenProvider;
import os.aiworkforce.knowledge.service.RetrievalService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;

/**
 * Document search for work an agent does, where there is no signed-in person to forward.
 *
 * <p>A chat message is searched with the person's own token. A scheduled run, a task from a goal
 * and a step an agent takes by itself have no such token, so the orchestrator asks here with a
 * service token that names the person the work is for. This endpoint refuses a person's own token
 * - they have the public search - and decides what the named person may read, never what the
 * service asking would like to.
 *
 * <p>A service token carries who it is for and no permissions at all, by design: it authenticates
 * the caller and grants nothing. So the person's permissions are asked of the identity service,
 * where roles and memberships live, and the answer is held for half a minute. When identity cannot
 * be asked, the search is refused as unavailable. It is never answered with workspace-wide access
 * because the check could not be made.
 *
 * <ul>
 *   <li>The person must hold {@code knowledge:query}, exactly as for the public search.
 *   <li>Restricted sources are read only when they also hold {@code knowledge:source_manage}.
 *       Anyone else searches as if those sources did not exist.
 *   <li>Work with no person behind it - the platform's own system actor - is refused outright.
 *       Documents are never searched on nobody's behalf.
 * </ul>
 */
@RestController
@RequestMapping("/internal/knowledge")
@Tag(name = "Internal")
public class InternalSearchController {

    private static final Logger log = LoggerFactory.getLogger(InternalSearchController.class);

    static final int DEFAULT_LIMIT = 5;

    /** What a person may do in a workspace. A seam, so tests need not stand up the identity service. */
    public interface PersonPermissions {

        /**
         * The permission codes the person holds in the workspace through their membership; empty
         * for someone who is not a member.
         *
         * @throws ApiException {@code DEPENDENCY_UNAVAILABLE} when the answer could not be had, so
         *     that a caller never mistakes "could not ask" for "may"
         */
        Set<String> permissionsOf(UUID orgId, UUID userId);
    }

    /**
     * @param query what to find, at most 1,000 characters
     * @param limit how many passages, {@value #DEFAULT_LIMIT} when left out
     * @param sourceIds narrow the search to these sources; one the person may not read is left out
     * @param agentId the agent searching: its own documents are searched too, and no other agent's
     * @param runId the run searching, for the record
     */
    public record InternalSearchRequest(
            @NotBlank @Size(max = 1_000) String query,
            @Min(1) @Max(RetrievalService.MAX_LIMIT) Integer limit,
            List<UUID> sourceIds,
            UUID agentId,
            UUID runId) {}

    /** @param indexedSources how many sources in the workspace hold at least one passage */
    public record Status(int indexedSources) {}

    private final RetrievalService retrieval;
    private final PersonPermissions people;

    public InternalSearchController(RetrievalService retrieval, PersonPermissions people) {
        this.retrieval = retrieval;
        this.people = people;
    }

    // @find: agent searches knowledge base, search tool, POST /internal/knowledge/search
    @PostMapping("/search")
    @Operation(summary = "Internal: search the knowledge base for a person an agent is working for")
    public KnowledgeController.SearchResponse search(
            @Valid @RequestBody InternalSearchRequest request,
            @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId) {
        Actor actor = requireService();
        UUID orgId = orgOf(workspaceId);

        UUID person = parseUuid(actor.humanId());
        if (person == null) {
            // Work with nobody behind it. The platform's own actor is not a person, and a search
            // made as nobody would be a search of everything.
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Documents are not available for this run.")
                    .with("reason", "no_requester");
        }

        Set<String> held = people.permissionsOf(orgId, person);
        if (!held.contains(Permission.Codes.KNOWLEDGE_QUERY)) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "The person this work is for may not search the workspace's documents.")
                    .with("requiredPermission", Permission.Codes.KNOWLEDGE_QUERY);
        }
        boolean restrictedToo = held.contains(Permission.Codes.KNOWLEDGE_SOURCE_MANAGE);

        log.info(
                "Agent search of workspace {} for person {} (agent {}, run {}, restricted sources {})",
                orgId,
                person,
                request.agentId(),
                request.runId(),
                restrictedToo ? "included" : "excluded");

        RetrievalService.Retrieval found = retrieval.retrieve(
                orgId,
                request.query(),
                request.limit() == null ? DEFAULT_LIMIT : request.limit(),
                request.sourceIds(),
                restrictedToo,
                request.agentId());
        return new KnowledgeController.SearchResponse(found.passages(), !found.passages().isEmpty(), found.degraded());
    }

    // @find: how many sources are indexed, GET /internal/knowledge/status
    @GetMapping("/status")
    @Operation(summary = "Internal: whether the workspace has any indexed documents")
    public Status status(
            @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
            @org.springframework.web.bind.annotation.RequestParam(name = "agentId", required = false) UUID agentId) {
        requireService();
        return new Status(retrieval.indexedSources(orgOf(workspaceId), agentId));
    }

    /** A person's own token must never reach this, whatever permissions it carries. */
    private static Actor requireService() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        return actor;
    }

    /** The workspace the token is for; a header naming another one is refused rather than followed. */
    private static UUID orgOf(UUID header) {
        UUID orgId = parseUuid(RequestContext.requireOrgId());
        if (orgId == null) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "No workspace is selected for this call.");
        }
        if (header != null && !header.equals(orgId)) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "This service token belongs to a different workspace.");
        }
        return orgId;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * Asks the identity service what a person holds in a workspace.
     *
     * <p>Held for {@value #TTL_SECONDS} seconds per person and workspace, so a run that searches
     * five times costs identity one call. That is also the longest a removed permission stays felt,
     * which is far inside the five minutes an access token already carries one.
     */
    @Component
    static class IdentityPermissions implements PersonPermissions {

        static final long TTL_SECONDS = 30;
        private static final int MAX_CACHED = 500;

        private record Key(UUID orgId, UUID userId) {}

        private record Cached(Set<String> permissions, Instant expiresAt) {
            boolean isFresh() {
                return Instant.now().isBefore(expiresAt);
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Answer(boolean member, List<String> permissions) {}

        private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
        private final WebClient client;
        private final InternalTokenProvider tokens;

        IdentityPermissions(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
            this.client = builder.clone().baseUrl(properties.services().identity()).build();
            this.tokens = tokens;
        }

        // @find: look up a person's permissions for restricted sources
        @Override
        public Set<String> permissionsOf(UUID orgId, UUID userId) {
            Key key = new Key(orgId, userId);
            Cached cached = cache.get(key);
            if (cached != null && cached.isFresh()) {
                return cached.permissions();
            }
            Set<String> permissions;
            try {
                Answer answer = client.post()
                        .uri("/internal/memberships/permissions")
                        .header("Authorization", "Bearer " + tokens.forService("identity"))
                        .bodyValue(Map.of("orgId", orgId.toString(), "userId", userId.toString()))
                        .retrieve()
                        .bodyToMono(Answer.class)
                        .timeout(Duration.ofSeconds(5))
                        .block();
                permissions = answer == null || !answer.member() || answer.permissions() == null
                        ? Set.of()
                        : Set.copyOf(answer.permissions());
            } catch (RuntimeException e) {
                log.warn("Could not read the permissions of person {} in workspace {}: {}", userId, orgId, e.getMessage());
                throw new ApiException(
                        ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "Could not check who this work is for, so no document was searched. Try again in a minute.");
            }
            if (cache.size() >= MAX_CACHED) {
                cache.values().removeIf(entry -> !entry.isFresh());
                if (cache.size() >= MAX_CACHED) {
                    cache.clear();
                }
            }
            cache.put(key, new Cached(permissions, Instant.now().plusSeconds(TTL_SECONDS)));
            return permissions;
        }
    }
}
