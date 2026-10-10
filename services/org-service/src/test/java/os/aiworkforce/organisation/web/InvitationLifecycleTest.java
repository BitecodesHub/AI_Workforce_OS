// @find: tests for invitations, create invitation, resend, revoke, accept, expired invitation, role grant check, existing member refused, invite beyond own role
// @what: Tests the invitation lifecycle end to end, including grant checks and refusal messages.
// @flow: Exercises InvitationController and InvitationService with a stubbed identity-service.
package os.aiworkforce.organisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.organisation.domain.Invitation;
import os.aiworkforce.organisation.repository.Invitations;
import os.aiworkforce.organisation.service.InternalTokenProvider;
import os.aiworkforce.organisation.service.InvitationService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * An invitation from creation to acceptance, through MockMvc with the real permission interceptor
 * and error handler, and with identity answered by a stub that records what it was asked.
 */
class InvitationLifecycleTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID INVITER = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID INVITEE = UUID.fromString("00000000-0000-7000-8000-0000000000c2");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<UUID, Invitation> rows = new LinkedHashMap<>();
    private final List<String> identityCalls = new ArrayList<>();
    private final Map<String, JsonNode> identityBodies = new LinkedHashMap<>();

    /** What identity answers, set per test. */
    private String checkGrantAnswer = "{\"allowed\":true}";

    private HttpStatus registerStatus = HttpStatus.CREATED;
    private HttpStatus bootstrapStatus = HttpStatus.OK;
    private String signedInEmail = "Nia.Newcomer@Example.test";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Invitations invitations = mock(Invitations.class);
        when(invitations.save(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(invitations.saveAndFlush(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(invitations.findByTokenHash(anyString()))
                .thenAnswer(call -> rows.values().stream()
                        .filter(row -> row.getTokenHash().equals(call.getArgument(0)))
                        .findFirst());
        when(invitations.findByIdAndOrgId(any(), any()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0)))
                        .filter(row -> row.getOrgId().equals(call.getArgument(1))));
        when(invitations.findByOrgIdAndEmailIgnoreCaseAndStatus(any(), anyString(), anyString()))
                .thenAnswer(call -> rows.values().stream()
                        .filter(row -> row.getOrgId().equals(call.getArgument(0))
                                && row.getEmail().equalsIgnoreCase(call.getArgument(1))
                                && row.getStatus().equals(call.getArgument(2)))
                        .findFirst());
        when(invitations.findByOrgIdOrderByCreatedAtDesc(any()))
                .thenAnswer(call -> rows.values().stream()
                        .filter(row -> row.getOrgId().equals(call.getArgument(0)))
                        .sorted(Comparator.comparing(Invitation::getId).reversed())
                        .toList());

        WebClient.Builder identity = WebClient.builder().exchangeFunction(this::identity);
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.services())
                .thenReturn(new PlatformProperties.Services(
                        "http://gateway",
                        "http://identity",
                        "http://organisation",
                        "http://orchestrator",
                        "http://memory",
                        "http://knowledge",
                        "http://integrations",
                        "http://analytics"));
        InternalTokenProvider tokens = mock(InternalTokenProvider.class);
        when(tokens.forService(anyString())).thenReturn("service-token");

        InvitationService service = new InvitationService(invitations, identity, properties, tokens);
        mvc = MockMvcBuilders.standaloneSetup(new InvitationController(service))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    // ---- Identity, stubbed ---------------------------------------------------------------------

    private Mono<ClientResponse> identity(ClientRequest request) {
        String path = request.url().getPath();
        identityCalls.add(request.method() + " " + path);
        if (request.method() != HttpMethod.GET) {
            identityBodies.put(path, bodyOf(request));
        }
        return Mono.just(
                switch (path) {
                    case "/internal/memberships/check-grant" -> json(HttpStatus.OK, checkGrantAnswer);
                    case "/api/auth/register" ->
                        registerStatus == HttpStatus.CREATED
                                ? ClientResponse.create(HttpStatus.CREATED)
                                        .header("X-User-Id", INVITEE.toString())
                                        .build()
                                : ClientResponse.create(registerStatus).build();
                    case "/internal/memberships/bootstrap-member" ->
                        bootstrapStatus == HttpStatus.OK
                                ? json(HttpStatus.OK, "{\"role\":\"employee\"}")
                                : json(
                                        bootstrapStatus,
                                        "{\"code\":\"permission_denied\",\"detail\":\"The person who sent this"
                                                + " invitation is no longer a member of the workspace.\"}");
                    case "/api/users/me" ->
                        "Bearer person-token".equals(request.headers().getFirst("Authorization"))
                                ? json(HttpStatus.OK, "{\"email\":\"" + signedInEmail + "\"}")
                                : ClientResponse.create(HttpStatus.UNAUTHORIZED).build();
                    default -> ClientResponse.create(HttpStatus.NOT_FOUND).build();
                });
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private Invitation store(Invitation invitation) {
        rows.put(invitation.getId(), invitation);
        return invitation;
    }

    // ---- Actors ------------------------------------------------------------------------------

    private static void signInAsInviter(String... permissions) {
        RequestContext.setActor(Actor.user(INVITER.toString(), ORG.toString(), "role", Set.of(permissions), 1L));
    }

    private static void signInAsInvitee() {
        // An account that belongs to no workspace yet: no organisation, no permissions.
        RequestContext.setActor(Actor.user(INVITEE.toString(), null, null, Set.of(), 0L));
    }

    // ---- Requests ----------------------------------------------------------------------------

    private ResultActions invite(String email, String roleName) throws Exception {
        return mvc.perform(post("/api/orgs/{orgId}/invitations", ORG)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:5173")
                .content("{\"email\":\"" + email + "\",\"roleName\":\"" + roleName + "\"}"));
    }

    /** Creates an invitation as an inviter allowed to give the role, and returns its raw token. */
    private String invited(String email) throws Exception {
        signInAsInviter(Permission.Codes.MEMBER_INVITE);
        String body = invite(email, "employee")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        RequestContext.clear();
        identityCalls.clear();
        return JSON.readTree(body).get("token").asText();
    }

    private ResultActions accept(String token) throws Exception {
        return mvc.perform(post("/api/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token
                        + "\",\"displayName\":\"Nia Newcomer\",\"password\":\"correct horse battery\"}"));
    }

    private ResultActions acceptSignedIn(String token) throws Exception {
        return mvc.perform(post("/api/invitations/accept-signed-in")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer person-token")
                .content("{\"token\":\"" + token + "\"}"));
    }

    private Invitation only() {
        return rows.values().stream()
                .filter(row -> !row.isRevoked())
                .findFirst()
                .orElseThrow();
    }

    // ---- Creating ----------------------------------------------------------------------------

    @Test
    @DisplayName("an invitation is checked with identity first, as the person sending it")
    void createChecksTheGrant() throws Exception {
        signInAsInviter(Permission.Codes.MEMBER_INVITE);

        invite("nia.newcomer@example.test", "employee")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptUrl", containsString("http://localhost:5173/accept-invite?token=")));

        JsonNode asked = identityBodies.get("/internal/memberships/check-grant");
        assertThat(asked.get("orgId").asText()).isEqualTo(ORG.toString());
        assertThat(asked.get("actorUserId").asText()).isEqualTo(INVITER.toString());
        assertThat(asked.get("roleName").asText()).isEqualTo("employee");
    }

    @Test
    @DisplayName("a role holding only member:invite that invites an admin is refused at creation")
    void inviteBeyondOwnRoleRefused() throws Exception {
        checkGrantAnswer = "{\"allowed\":false,\"reason\":\"exceeds_grantor\"}";
        signInAsInviter(Permission.Codes.MEMBER_INVITE);

        invite("nia.newcomer@example.test", "admin")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"))
                .andExpect(jsonPath("$.errors.reason").value("exceeds_grantor"));
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("an address that already belongs to an active member is refused on the email field, nothing stored")
    void existingMemberRefused() throws Exception {
        checkGrantAnswer = "{\"allowed\":false,\"reason\":\"already_member\"}";
        signInAsInviter(Permission.Codes.MEMBER_INVITE);

        invite("Viewer@Example.test", "employee")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("email"));
        assertThat(rows).isEmpty();
        // The address is sent, normalised, so identity can tell.
        assertThat(identityBodies.get("/internal/memberships/check-grant").get("email").asText())
                .isEqualTo("viewer@example.test");
    }

    @Test
    @DisplayName("an unknown role is a validation error on the role field, and nothing is stored")
    void unknownRoleRefused() throws Exception {
        checkGrantAnswer = "{\"allowed\":false,\"reason\":\"unknown_role\"}";
        signInAsInviter(Permission.Codes.MEMBER_INVITE);

        invite("nia.newcomer@example.test", "astronaut")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("roleName"));
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("inviting the same address again replaces the open invitation, which is what Resend does")
    void reinviteReplaces() throws Exception {
        String first = invited("nia.newcomer@example.test");
        String second = invited("Nia.Newcomer@example.test");

        assertThat(rows.values()).extracting(Invitation::getStatus).containsExactlyInAnyOrder("revoked", "pending");
        accept(first).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.reason").value("revoked"));
        assertThat(second).isNotEqualTo(first);
    }

    // ---- Revoking ----------------------------------------------------------------------------

    @Test
    @DisplayName("a revoked invitation is refused at both doors with a clear withdrawn message")
    void revokedInvitationRefused() throws Exception {
        String token = invited("nia.newcomer@example.test");
        UUID id = only().getId();

        signInAsInviter(Permission.Codes.MEMBER_INVITE);
        mvc.perform(delete("/api/orgs/{orgId}/invitations/{id}", ORG, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"));
        RequestContext.clear();

        accept(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("This invitation was withdrawn")))
                .andExpect(jsonPath("$.errors.reason").value("revoked"));

        signInAsInvitee();
        acceptSignedIn(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("This invitation was withdrawn")));
        assertThat(identityCalls).noneMatch(call -> call.contains("register") || call.contains("bootstrap"));
    }

    @Test
    @DisplayName("revoking needs member:invite, the same workspace, and an invitation not yet accepted")
    void revokeIsGuarded() throws Exception {
        invited("nia.newcomer@example.test");
        UUID id = only().getId();

        signInAsInviter(Permission.Codes.MEMBER_READ);
        mvc.perform(delete("/api/orgs/{orgId}/invitations/{id}", ORG, id)).andExpect(status().isForbidden());

        signInAsInviter(Permission.Codes.MEMBER_INVITE);
        mvc.perform(delete("/api/orgs/{orgId}/invitations/{id}", OTHER_ORG, id))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organisation_mismatch"));

        only().setStatus("accepted");
        mvc.perform(delete("/api/orgs/{orgId}/invitations/{id}", ORG, id)).andExpect(status().isConflict());
        assertThat(only().getStatus()).isEqualTo("accepted");
    }

    // ---- Accepting with a new account --------------------------------------------------------

    @Test
    @DisplayName("an address that already has an account is told to sign in, with a reason the client can read")
    void existingAccountMustSignIn() throws Exception {
        String token = invited("nia.newcomer@example.test");
        registerStatus = HttpStatus.CONFLICT;

        accept(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_exists"))
                .andExpect(jsonPath("$.errors.reason").value("account_exists"))
                .andExpect(jsonPath("$.errors.signInRequired").value(true));
        assertThat(only().isPending()).isTrue();
    }

    @Test
    @DisplayName("an invitation whose sender can no longer give the role is refused before any account is made")
    void acceptRechecksTheGrantFirst() throws Exception {
        String token = invited("nia.newcomer@example.test");
        checkGrantAnswer = "{\"allowed\":false,\"reason\":\"exceeds_grantor\"}";

        accept(token).andExpect(status().isForbidden());

        assertThat(identityCalls).containsExactly("POST /internal/memberships/check-grant");
        assertThat(only().isPending()).isTrue();
    }

    @Test
    @DisplayName("a new account is registered and granted the role, naming the inviter and the address")
    void acceptWithNewAccount() throws Exception {
        String token = invited("nia.newcomer@example.test");

        accept(token).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(INVITEE.toString()));

        JsonNode granted = identityBodies.get("/internal/memberships/bootstrap-member");
        assertThat(granted.get("invitedBy").asText()).isEqualTo(INVITER.toString());
        assertThat(granted.get("email").asText()).isEqualTo("nia.newcomer@example.test");
        assertThat(only().getStatus()).isEqualTo("accepted");
    }

    // ---- Accepting as the signed-in account --------------------------------------------------

    @Test
    @DisplayName("accept-signed-in with a different account's address is refused, and nothing is granted")
    void signedInEmailMustMatch() throws Exception {
        String token = invited("nia.newcomer@example.test");
        signedInEmail = "someone.else@example.test";
        signInAsInvitee();

        acceptSignedIn(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("email_mismatch"));

        assertThat(identityCalls).doesNotContain("POST /internal/memberships/bootstrap-member");
        assertThat(only().isPending()).isTrue();
    }

    @Test
    @DisplayName("accept-signed-in with the invited address, in any case, joins and marks the invitation used")
    void signedInAcceptJoins() throws Exception {
        String token = invited("nia.newcomer@example.test");
        signInAsInvitee();

        acceptSignedIn(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(ORG.toString()))
                .andExpect(jsonPath("$.roleName").value("employee"));

        assertThat(identityCalls).doesNotContain("POST /api/auth/register");
        JsonNode granted = identityBodies.get("/internal/memberships/bootstrap-member");
        assertThat(granted.get("userId").asText()).isEqualTo(INVITEE.toString());
        assertThat(granted.get("invitedBy").asText()).isEqualTo(INVITER.toString());
        assertThat(only().getStatus()).isEqualTo("accepted");
        assertThat(only().getAcceptedAt()).isNotNull();
    }

    @Test
    @DisplayName("an earlier acceptance that made the account but not the membership is finished by signing in")
    void signedInFinishesAHalfAcceptance() throws Exception {
        String token = invited("nia.newcomer@example.test");
        bootstrapStatus = HttpStatus.BAD_GATEWAY;
        accept(token)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", containsString("Sign in to accept")));
        assertThat(only().isPending()).isTrue();

        bootstrapStatus = HttpStatus.OK;
        signInAsInvitee();
        acceptSignedIn(token).andExpect(status().isOk());
        assertThat(only().getStatus()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("identity refusing the grant at acceptance comes back as 403 with its own words")
    void identityRefusalPassesThrough() throws Exception {
        String token = invited("nia.newcomer@example.test");
        bootstrapStatus = HttpStatus.FORBIDDEN;
        signInAsInvitee();

        acceptSignedIn(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("no longer a member")));
        assertThat(only().isPending()).isTrue();
    }

    @Test
    @DisplayName("an expired invitation is refused and recorded as expired")
    void expiredRefused() throws Exception {
        String token = invited("nia.newcomer@example.test");
        only().setExpiresAt(Instant.now().minusSeconds(60));
        signInAsInvitee();

        acceptSignedIn(token).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.reason").value("expired"));
        assertThat(rows.values()).extracting(Invitation::getStatus).contains("expired");
    }

    @Test
    @DisplayName("a service token cannot accept as a person")
    void serviceTokenRefused() throws Exception {
        String token = invited("nia.newcomer@example.test");
        RequestContext.setActor(
                new Actor("orchestrator", Actor.Kind.SYSTEM, ORG.toString(), null, Set.of(), 0L, null, null, null, null));

        acceptSignedIn(token).andExpect(status().isForbidden());
        assertThat(Objects.requireNonNull(only()).isPending()).isTrue();
    }

    /** The JSON body a request would send, written into a mock request to read it back. */
    private static JsonNode bodyOf(ClientRequest request) {
        MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
        BodyInserter<?, ? super ClientHttpRequest> inserter = request.body();
        inserter.insert(written, new BodyInserter.Context() {
                    @Override
                    public List<HttpMessageWriter<?>> messageWriters() {
                        return ExchangeStrategies.withDefaults().messageWriters();
                    }

                    @Override
                    public Optional<ServerHttpRequest> serverRequest() {
                        return Optional.empty();
                    }

                    @Override
                    public Map<String, Object> hints() {
                        return Map.of();
                    }
                })
                .block();
        try {
            return JSON.readTree(written.getBodyAsString().block());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
