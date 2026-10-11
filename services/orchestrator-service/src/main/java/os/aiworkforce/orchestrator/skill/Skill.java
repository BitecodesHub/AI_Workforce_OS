// @find: skill entity, playbook, reusable instructions, skill record, name, description, instructions, default agent, required connectors, built in skill, skills table, Skill
// @what: JPA entity for one skill: a workspace's reusable playbook a person applies to a chat message.
// @flow: Stored in the skills table; handled by SkillService and Skills
package os.aiworkforce.orchestrator.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * A reusable playbook: markdown instructions applied to the run of the chat message it is used
 * with.
 *
 * <p>{@code defaultAgentId} is deliberately not a foreign key: retiring or deleting an agent must
 * not delete the playbook, and a missing agent is simply ignored when the skill is used. {@code
 * requiredConnectors} is shown to the person and never enforced here; the agent's own grants
 * decide what it may actually call.
 */
@Entity
@Table(name = "skills")
public class Skill extends OrgScopedEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 500)
    private String description = "";

    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @Column(name = "default_agent_id")
    private UUID defaultAgentId;

    /** Connector server ids ({@code github}, {@code gmail}...), lowercase and distinct. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_connectors", nullable = false)
    private List<String> requiredConnectors = new ArrayList<>();

    /** Whether this row was one of the ready-made skills a workspace is given once. */
    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public UUID getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(UUID defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public List<String> getRequiredConnectors() {
        return requiredConnectors;
    }

    public void setRequiredConnectors(List<String> requiredConnectors) {
        this.requiredConnectors = requiredConnectors == null ? new ArrayList<>() : new ArrayList<>(requiredConnectors);
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }
}
