// @find: agent tool grant, tool permissions, what an agent may use, allowed tools, scopes, require approval, max calls per run, least privilege, grant tool server to agent, agent_tool_grants, Tools tab, AgentToolGrant
// @what: Entity recording which tool server and tools one agent may use, with scopes, approval gate and per-run call ceiling.
// @flow: Read from ToolGrants repository when a run builds its tool list.
// @find: agent tool grant, tool permissions, what an agent may use, allowed tools, scopes, require approval, max calls per run, least privilege, grant tool server to agent, agent_tool_grants, Tools tab, AgentToolGrant
// @what: Entity recording which tool server and tools one agent may use, with scopes, approval gate and per-run call ceiling.
// @flow: Read from ToolGrants repository when a run builds its tool list.
package os.aiworkforce.orchestrator.domain;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * What one agent is permitted to do with one tool server.
 *
 * <p>Least privilege, expressed as a row. An empty {@code allowedTools} means the whole server,
 * which is reasonable for a read-only one and is deliberately not the default for anything that
 * can send. {@code requireApproval} can add a gate but never remove the one a destructive or
 * outbound tool carries by nature.
 */
@Entity
@Table(name = "agent_tool_grants")
public class AgentToolGrant extends OrgScopedEntity {

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private String server;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_tools", nullable = false)
    private List<String> allowedTools = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> scopes = List.of();

    @Column(name = "require_approval", nullable = false)
    private boolean requireApproval;

    /** A ceiling per run, so one looping agent cannot exhaust a workspace's quota with a vendor. */
    @Column(name = "max_calls_per_run")
    private Integer maxCallsPerRun;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public List<String> getAllowedTools() {
        return allowedTools == null ? List.of() : allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public List<String> getScopes() {
        return scopes == null ? List.of() : scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public boolean isRequireApproval() {
        return requireApproval;
    }

    public void setRequireApproval(boolean requireApproval) {
        this.requireApproval = requireApproval;
    }

    public Integer getMaxCallsPerRun() {
        return maxCallsPerRun;
    }

    public void setMaxCallsPerRun(Integer maxCallsPerRun) {
        this.maxCallsPerRun = maxCallsPerRun;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
