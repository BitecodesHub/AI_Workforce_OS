// @find: permissions, permission codes, role builder checkboxes, rbac, agent:read, audit:read, memory:read, workspace:update, planned permissions, permission registry
// @what: Registry of every permission code the platform knows, which the console shows and endpoints require.
// @flow: Seeded by identity-service PermissionSeeder; enforced via @RequiresPermission
package os.aiworkforce.platform.rbac;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The registry of permission codes the platform understands.
 *
 * <p>A deliberate division of labour, and the reason this is not a contradiction of
 * "permissions live in the database":
 *
 * <ul>
 *   <li><b>Codes are code.</b> A permission only means something because some endpoint checks
 *       for it. Inventing {@code agent:teleport} in a database row would grant nothing, so the
 *       set of valid codes is fixed by the build and validated at startup.
 *   <li><b>Roles are data.</b> Which codes make up {@code manager}, whether a workspace adds a
 *       {@code reviewer} role, and who holds which role are rows, edited in the console, with no
 *       deployment involved. That is where the flexibility an operator actually needs lives.
 * </ul>
 *
 * <p>Each entry also declares whether it is <em>administrative</em> - meaning it can change who
 * can do what - so the console can group them and warn before granting them.
 *
 * <p>An entry can also be <em>planned</em>: a code reserved for a feature no endpoint checks yet.
 * A planned code stays known, so a stored role that already carries it is still valid and the
 * seeder still writes it, but it is left out of everything that offers or describes permissions
 * to a person. A checkbox that grants nothing would otherwise read as a capability the product
 * does not have.
 *
 * @param code the wire form, {@code resource:action}
 * @param resource the thing being acted on
 * @param action the verb
 * @param description one sentence shown beside the checkbox in the console
 * @param administrative whether holding this permission lets an account widen its own authority
 * @param planned whether no endpoint checks this code yet, so the console should not offer it
 */
public record Permission(
        String code, String resource, String action, String description, boolean administrative, boolean planned) {

    public Permission {
        Objects.requireNonNull(code, "code");
        if (!code.equals(resource + ":" + action)) {
            throw new IllegalArgumentException("Permission code must be resource:action, got " + code);
        }
    }

    private static Permission of(String resource, String action, String description) {
        return new Permission(resource + ":" + action, resource, action, description, false, false);
    }

    private static Permission admin(String resource, String action, String description) {
        return new Permission(resource + ":" + action, resource, action, description, true, false);
    }

    /**
     * Marks a code as reserved for a feature that does not exist yet.
     *
     * <p>Only after a search of every service finds no check for it. Flipping it back is the one
     * line change that goes with the endpoint that starts enforcing it.
     */
    private static Permission asPlanned(Permission permission) {
        return new Permission(
                permission.code(),
                permission.resource(),
                permission.action(),
                permission.description(),
                permission.administrative(),
                true);
    }

    // ---- Workspace -----------------------------------------------------------------------
    public static final Permission WORKSPACE_READ = of("workspace", "read", "View workspace details and settings.");
    public static final Permission WORKSPACE_UPDATE =
            admin("workspace", "update", "Change workspace name, working hours and general settings.");
    public static final Permission WORKSPACE_DELETE =
            asPlanned(admin("workspace", "delete", "Permanently close the workspace."));

    // ---- Members and roles ---------------------------------------------------------------
    public static final Permission MEMBER_READ = of("member", "read", "See who belongs to the workspace.");
    public static final Permission MEMBER_INVITE = admin("member", "invite", "Invite people to the workspace.");
    public static final Permission MEMBER_UPDATE =
            admin("member", "update", "Change a member's role or suspend their access.");
    public static final Permission MEMBER_REMOVE = admin("member", "remove", "Remove a member from the workspace.");
    public static final Permission ROLE_READ = of("role", "read", "View roles and the permissions they carry.");
    public static final Permission ROLE_CREATE = admin("role", "create", "Create a new role.");
    public static final Permission ROLE_UPDATE = admin("role", "update", "Change the permissions a role carries.");
    public static final Permission ROLE_DELETE = admin("role", "delete", "Delete a role that nobody holds.");
    public static final Permission API_KEY_READ =
            asPlanned(of("api_key", "read", "List machine keys and their scopes."));
    public static final Permission API_KEY_MANAGE =
            asPlanned(admin("api_key", "manage", "Create and revoke machine keys."));

    // ---- Agents --------------------------------------------------------------------------
    public static final Permission AGENT_READ = of("agent", "read", "View agents and how they are configured.");
    public static final Permission AGENT_CREATE = of("agent", "create", "Add an agent to the workspace.");
    public static final Permission AGENT_UPDATE =
            of("agent", "update", "Change an agent's persona, goals and parameters.");
    public static final Permission AGENT_DELETE = of("agent", "delete", "Retire an agent.");
    public static final Permission AGENT_RUN = of("agent", "run", "Give an agent work to do.");
    public static final Permission AGENT_GRANT_TOOLS =
            admin("agent", "grant_tools", "Decide which tools an agent may use, and at what scope.");
    public static final Permission AGENT_SET_MODEL_POLICY =
            of("agent", "set_model_policy", "Choose which models an agent uses and in what order.");
    public static final Permission AGENT_SET_APPROVAL_POLICY =
            admin("agent", "set_approval_policy", "Decide which of an agent's actions need approval.");

    // ---- Work ----------------------------------------------------------------------------
    public static final Permission TASK_READ = of("task", "read", "View tasks and goals.");
    public static final Permission TASK_CREATE = of("task", "create", "Create a goal or a task.");
    public static final Permission TASK_CANCEL = of("task", "cancel", "Cancel a task that is running or queued.");
    public static final Permission RUN_READ = of("run", "read", "View run traces, tool calls and evidence.");
    public static final Permission RUN_CANCEL = of("run", "cancel", "Stop a run in progress.");
    public static final Permission RUN_REPLAY =
            asPlanned(admin("run", "replay", "Replay a failed run or a dead-lettered event."));
    public static final Permission CHAT_USE = of("chat", "use", "Ask questions in the chat workspace.");
    public static final Permission CHAT_READ_ALL =
            admin("chat", "read_all", "Read private conversations that belong to other people. Every such read is logged.");

    // ---- Approvals -----------------------------------------------------------------------
    public static final Permission APPROVAL_READ = of("approval", "read", "See the approvals queue.");
    public static final Permission APPROVAL_DECIDE =
            of("approval", "decide", "Approve or reject an action an agent is waiting on.");

    // ---- Knowledge -----------------------------------------------------------------------
    public static final Permission KNOWLEDGE_READ = of("knowledge", "read", "View knowledge sources and their status.");
    public static final Permission KNOWLEDGE_QUERY = of("knowledge", "query", "Search the knowledge base.");
    public static final Permission KNOWLEDGE_SOURCE_MANAGE =
            of("knowledge", "source_manage", "Connect, reindex and disconnect knowledge sources.");

    // ---- Integrations --------------------------------------------------------------------
    public static final Permission INTEGRATION_READ =
            of("integration", "read", "View connected tools and their permissions.");
    public static final Permission INTEGRATION_CONNECT =
            admin("integration", "connect", "Connect a tool and authorise the scopes it requests.");
    public static final Permission INTEGRATION_DISCONNECT =
            admin("integration", "disconnect", "Disconnect a tool and revoke its stored credentials.");

    // ---- Models and spend ----------------------------------------------------------------
    public static final Permission PROVIDER_READ =
            of("provider", "read", "View configured model providers and their health.");
    public static final Permission PROVIDER_MANAGE =
            admin("provider", "manage", "Add providers and models, and store their credentials.");
    public static final Permission BUDGET_READ = of("budget", "read", "View language model spend against the budget.");
    public static final Permission BUDGET_MANAGE = admin("budget", "manage", "Set spending caps for the workspace.");

    // ---- Governance ----------------------------------------------------------------------
    public static final Permission AUDIT_READ = of("audit", "read", "Read the audit log.");
    public static final Permission ANALYTICS_READ = of("analytics", "read", "View dashboards and reports.");
    public static final Permission SETTINGS_READ = asPlanned(of("settings", "read", "View runtime settings."));
    public static final Permission SETTINGS_UPDATE =
            asPlanned(admin("settings", "update", "Change runtime settings for the workspace."));
    public static final Permission MEMORY_READ = of("memory", "read", "Inspect what agents have remembered.");
    public static final Permission MEMORY_PURGE = asPlanned(admin("memory", "purge", "Erase stored agent memory."));

    /** Every permission the build understands, in console display order. */
    public static final List<Permission> ALL = List.of(
            WORKSPACE_READ,
            WORKSPACE_UPDATE,
            WORKSPACE_DELETE,
            MEMBER_READ,
            MEMBER_INVITE,
            MEMBER_UPDATE,
            MEMBER_REMOVE,
            ROLE_READ,
            ROLE_CREATE,
            ROLE_UPDATE,
            ROLE_DELETE,
            API_KEY_READ,
            API_KEY_MANAGE,
            AGENT_READ,
            AGENT_CREATE,
            AGENT_UPDATE,
            AGENT_DELETE,
            AGENT_RUN,
            AGENT_GRANT_TOOLS,
            AGENT_SET_MODEL_POLICY,
            AGENT_SET_APPROVAL_POLICY,
            TASK_READ,
            TASK_CREATE,
            TASK_CANCEL,
            RUN_READ,
            RUN_CANCEL,
            RUN_REPLAY,
            CHAT_USE,
            CHAT_READ_ALL,
            APPROVAL_READ,
            APPROVAL_DECIDE,
            KNOWLEDGE_READ,
            KNOWLEDGE_QUERY,
            KNOWLEDGE_SOURCE_MANAGE,
            INTEGRATION_READ,
            INTEGRATION_CONNECT,
            INTEGRATION_DISCONNECT,
            PROVIDER_READ,
            PROVIDER_MANAGE,
            BUDGET_READ,
            BUDGET_MANAGE,
            AUDIT_READ,
            ANALYTICS_READ,
            SETTINGS_READ,
            SETTINGS_UPDATE,
            MEMORY_READ,
            MEMORY_PURGE);

    private static final Map<String, Permission> BY_CODE =
            ALL.stream().collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    /**
     * The same codes as compile-time string constants.
     *
     * <p>Java annotations accept only constant expressions, so {@code @RequiresPermission} cannot
     * call {@link Permission#code()}. These constants exist purely so an endpoint can declare its
     * requirement inline, and a startup check asserts that every one of them still resolves to a
     * registered permission - so a rename here cannot silently orphan an annotation.
     */
    public static final class Codes {

        private Codes() {}

        public static final String WORKSPACE_READ = "workspace:read";
        public static final String WORKSPACE_UPDATE = "workspace:update";
        public static final String WORKSPACE_DELETE = "workspace:delete";
        public static final String MEMBER_READ = "member:read";
        public static final String MEMBER_INVITE = "member:invite";
        public static final String MEMBER_UPDATE = "member:update";
        public static final String MEMBER_REMOVE = "member:remove";
        public static final String ROLE_READ = "role:read";
        public static final String ROLE_CREATE = "role:create";
        public static final String ROLE_UPDATE = "role:update";
        public static final String ROLE_DELETE = "role:delete";
        public static final String API_KEY_READ = "api_key:read";
        public static final String API_KEY_MANAGE = "api_key:manage";
        public static final String AGENT_READ = "agent:read";
        public static final String AGENT_CREATE = "agent:create";
        public static final String AGENT_UPDATE = "agent:update";
        public static final String AGENT_DELETE = "agent:delete";
        public static final String AGENT_RUN = "agent:run";
        public static final String AGENT_GRANT_TOOLS = "agent:grant_tools";
        public static final String AGENT_SET_MODEL_POLICY = "agent:set_model_policy";
        public static final String AGENT_SET_APPROVAL_POLICY = "agent:set_approval_policy";
        public static final String TASK_READ = "task:read";
        public static final String TASK_CREATE = "task:create";
        public static final String TASK_CANCEL = "task:cancel";
        public static final String RUN_READ = "run:read";
        public static final String RUN_CANCEL = "run:cancel";
        public static final String RUN_REPLAY = "run:replay";
        public static final String CHAT_USE = "chat:use";
        public static final String CHAT_READ_ALL = "chat:read_all";
        public static final String APPROVAL_READ = "approval:read";
        public static final String APPROVAL_DECIDE = "approval:decide";
        public static final String KNOWLEDGE_READ = "knowledge:read";
        public static final String KNOWLEDGE_QUERY = "knowledge:query";
        public static final String KNOWLEDGE_SOURCE_MANAGE = "knowledge:source_manage";
        public static final String INTEGRATION_READ = "integration:read";
        public static final String INTEGRATION_CONNECT = "integration:connect";
        public static final String INTEGRATION_DISCONNECT = "integration:disconnect";
        public static final String PROVIDER_READ = "provider:read";
        public static final String PROVIDER_MANAGE = "provider:manage";
        public static final String BUDGET_READ = "budget:read";
        public static final String BUDGET_MANAGE = "budget:manage";
        public static final String AUDIT_READ = "audit:read";
        public static final String ANALYTICS_READ = "analytics:read";
        public static final String SETTINGS_READ = "settings:read";
        public static final String SETTINGS_UPDATE = "settings:update";
        public static final String MEMORY_READ = "memory:read";
        public static final String MEMORY_PURGE = "memory:purge";
    }

    /**
     * Every permission a person can be offered, in console display order: {@link #ALL} without the
     * planned codes. This is the catalogue the role builder and the profile page show.
     */
    public static List<Permission> available() {
        return ALL.stream().filter(permission -> !permission.planned()).toList();
    }

    /**
     * Whether a code is reserved for a feature no endpoint checks yet.
     *
     * <p>False for an unknown code: the question only has an answer for a code the build knows.
     */
    public static boolean isPlanned(String code) {
        Permission permission = code == null ? null : BY_CODE.get(code);
        return permission != null && permission.planned();
    }

    /**
     * Whether a code is one this build knows, planned codes included.
     *
     * <p>Planned codes are accepted on purpose: seeded and custom roles already store them, and
     * refusing them here would make those roles impossible to save again.
     *
     * <p>Null-safe by design: the argument arrives from a request body and from annotation
     * metadata, and an immutable map throws on a null key. A permission check that crashes is a
     * 500 where a 422 was meant, so the absent case is answered rather than propagated.
     */
    public static boolean isKnown(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    public static Permission byCode(String code) {
        Permission permission = code == null ? null : BY_CODE.get(code);
        if (permission == null) {
            throw new IllegalArgumentException("Unknown permission code: " + code);
        }
        return permission;
    }

    public static Stream<Permission> forResource(String resource) {
        return ALL.stream().filter(p -> p.resource().equals(resource));
    }

    /** Codes that let an account widen its own authority; the console warns before granting them. */
    public static List<String> administrativeCodes() {
        return ALL.stream()
                .filter(Permission::administrative)
                .map(Permission::code)
                .toList();
    }
}
