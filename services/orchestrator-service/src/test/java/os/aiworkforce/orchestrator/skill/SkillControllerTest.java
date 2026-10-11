// @find: tests for skill controller, skill, permissions per endpoint, chat use reads, agent update writes, chat use only actor refused, view field names, agent name resolved, missing agent name, other workspace not found, SkillControllerTest, SkillController
// @what: Tests for SkillController in the orchestrator skill package: the permission each endpoint needs, enforced by the real interceptor, and the view it hands back.
// @flow: Exercises SkillController with PermissionInterceptor
package os.aiworkforce.orchestrator.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** Who may call each skills endpoint, and what each hands back, always out of the caller's workspace. */
class SkillControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private SkillService service;
    private Agents agents;
    private SkillController controller;
    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    @BeforeEach
    void setUp() {
        service = mock(SkillService.class);
        agents = mock(Agents.class);
        controller = new SkillController(service, agents);
        signIn(Permission.Codes.CHAT_USE, Permission.Codes.AGENT_UPDATE);
        when(agents.findByIdAndOrgId(any(), any())).thenReturn(Optional.empty());
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static void signIn(String... permissions) {
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(permissions), 0L));
    }

    private Skill skill(String name) {
        Skill skill = new Skill();
        skill.setId(UUID.randomUUID());
        skill.setOrgId(ORG);
        skill.setName(name);
        skill.setDescription("What it does");
        skill.setInstructions("Do it.");
        skill.setRequiredConnectors(List.of("github"));
        return skill;
    }

    /** Runs the interceptor for one controller method, as the dispatcher would before calling it. */
    private ApiException refusalFor(String name, Class<?>... parameters) throws Exception {
        Method method = SkillController.class.getMethod(name, parameters);
        HandlerMethod handler = new HandlerMethod(controller, method);
        try {
            interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler);
            return null;
        } catch (ApiException e) {
            return e;
        }
    }

    private static String[] permission(String name, Class<?>... parameters) throws Exception {
        return SkillController.class.getMethod(name, parameters).getAnnotation(RequiresPermission.class).value();
    }

    // ---- Permissions ---------------------------------------------------------------------------

    // @find: test permission per endpoint, skill controller
    @Test
    @DisplayName("reading needs chat:use; creating, editing, deleting and copying need agent:update")
    void permissionPerEndpoint() throws Exception {
        assertThat(permission("list")).containsExactly(Permission.Codes.CHAT_USE);
        assertThat(permission("get", UUID.class)).containsExactly(Permission.Codes.CHAT_USE);
        assertThat(permission("create", SkillController.SkillRequest.class))
                .containsExactly(Permission.Codes.AGENT_UPDATE);
        assertThat(permission("update", UUID.class, SkillController.SkillRequest.class))
                .containsExactly(Permission.Codes.AGENT_UPDATE);
        assertThat(permission("delete", UUID.class)).containsExactly(Permission.Codes.AGENT_UPDATE);
        assertThat(permission("duplicate", UUID.class)).containsExactly(Permission.Codes.AGENT_UPDATE);
    }

    // @find: test chat use only actor refused writes, skill controller
    @Test
    @DisplayName("someone who can only chat can list and read skills, and is refused every change with 403")
    void chatUseOnly() throws Exception {
        signIn(Permission.Codes.CHAT_USE);

        assertThat(refusalFor("list")).isNull();
        assertThat(refusalFor("get", UUID.class)).isNull();
        for (ApiException refused : List.of(
                refusalFor("create", SkillController.SkillRequest.class),
                refusalFor("update", UUID.class, SkillController.SkillRequest.class),
                refusalFor("delete", UUID.class),
                refusalFor("duplicate", UUID.class))) {
            assertThat(refused).isNotNull();
            assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
            assertThat(refused.code().status()).isEqualTo(403);
        }
    }

    // @find: test no permission refused reads, skill controller
    @Test
    @DisplayName("someone without chat:use cannot even list skills")
    void noChatUse() throws Exception {
        signIn(Permission.Codes.AGENT_READ);

        assertThat(refusalFor("list").code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(refusalFor("get", UUID.class).code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    // ---- Views ---------------------------------------------------------------------------------

    // @find: test view field names, skill controller
    @Test
    @DisplayName("a skill serialises with exactly the field names the console reads")
    void viewFieldNames() throws Exception {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("Sam");
        Skill skill = skill("Digest");
        skill.setDefaultAgentId(agent.getId());
        when(service.get(ORG, skill.getId())).thenReturn(skill);
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));

        SkillController.SkillView view = controller.get(skill.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .convertValue(view, Map.class);

        assertThat(json)
                .containsOnlyKeys(
                        "id",
                        "name",
                        "description",
                        "instructions",
                        "defaultAgentId",
                        "defaultAgentName",
                        "requiredConnectors",
                        "builtIn",
                        "createdBy",
                        "createdAt",
                        "updatedAt");
        assertThat(json.get("id")).isEqualTo(skill.getId().toString());
        assertThat(json.get("defaultAgentId")).isEqualTo(agent.getId().toString());
        assertThat(json.get("defaultAgentName")).isEqualTo("Sam");
        assertThat(json.get("requiredConnectors")).isEqualTo(List.of("github"));
        assertThat(json.get("builtIn")).isEqualTo(false);
    }

    // @find: test list resolves agent names once, skill controller
    @Test
    @DisplayName("the list names each default agent, and leaves the name empty for none or one since removed")
    void listResolvesAgentNames() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setName("Sam");
        Skill withAgent = skill("A");
        withAgent.setDefaultAgentId(agent.getId());
        Skill gone = skill("B");
        gone.setDefaultAgentId(UUID.randomUUID());
        Skill none = skill("C");
        when(service.list(ORG)).thenReturn(List.of(withAgent, gone, none));
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));

        List<SkillController.SkillView> views = controller.list();

        assertThat(views).extracting(SkillController.SkillView::defaultAgentName).containsExactly("Sam", null, null);
        assertThat(views.get(1).defaultAgentId()).isEqualTo(gone.getDefaultAgentId().toString());
        assertThat(views.get(2).defaultAgentId()).isNull();
    }

    // @find: test requests pass through to the service in the callers workspace, skill controller
    @Test
    @DisplayName("every change goes to the service for the caller's own workspace, with the request as sent")
    void changesUseCallersWorkspace() {
        Skill skill = skill("Digest");
        UUID agentId = UUID.randomUUID();
        SkillController.SkillRequest request =
                new SkillController.SkillRequest("Digest", null, "Do it.", agentId, List.of("github"));
        SkillService.SkillInput expected =
                new SkillService.SkillInput("Digest", null, "Do it.", agentId, List.of("github"));
        when(service.create(ORG, expected)).thenReturn(skill);
        when(service.update(ORG, skill.getId(), expected)).thenReturn(skill);
        when(service.duplicate(ORG, skill.getId())).thenReturn(skill);

        controller.create(request);
        controller.update(skill.getId(), request);
        controller.duplicate(skill.getId());
        controller.delete(skill.getId());

        verify(service).create(ORG, expected);
        verify(service).update(eq(ORG), eq(skill.getId()), eq(expected));
        verify(service).duplicate(ORG, skill.getId());
        verify(service).delete(ORG, skill.getId());
    }

    // @find: test another workspaces skill is not found, skill controller
    @Test
    @DisplayName("another workspace's skill is a 404, as the service reports it")
    void otherWorkspaceNotFound() {
        UUID theirs = UUID.randomUUID();
        when(service.get(ORG, theirs)).thenThrow(ApiException.notFound("skill", theirs));

        ApiException refused = catchThrowableOfType(() -> controller.get(theirs), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refused.code().status()).isEqualTo(404);
    }
}
