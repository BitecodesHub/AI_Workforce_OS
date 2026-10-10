// @find: agent, AI employee, digital worker, workforce member, create agent, rename agent, archive agent, restore agent, agent status, fallback agent, voice, voiceId, agents table, Agents page, Agent entity
// @what: Database entity for one AI employee (agent) in a workspace: key, name, category, status, current version, owner, voice.
// @flow: Stored by Agents repository; configured by AgentVersion; used by Run and AgentToolGrant.
// @find: agent, AI employee, digital worker, workforce member, create agent, rename agent, archive agent, restore agent, agent status, fallback agent, voice, voiceId, agents table, Agents page, Agent entity
// @what: Database entity for one AI employee (agent) in a workspace: key, name, category, status, current version, owner, voice.
// @flow: Stored by Agents repository; configured by AgentVersion; used by Run and AgentToolGrant.
package os.aiworkforce.orchestrator.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/** One AI employee in one workspace. */
@Entity
@Table(name = "agents")
public class Agent extends OrgScopedEntity {

    /** Stable within a workspace, used in URLs and in the tool grant tables. */
    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category = "operations";

    /**
     * What the agent does, in one line written about it rather than to it ("Screens applications
     * and books interviews"). Null when nobody has written one; the console then derives a line
     * from the instructions (V20).
     */
    @Column
    private String description;

    @Column(nullable = false)
    private String status = "active";

    /**
     * The version currently in force.
     *
     * <p>A run pins its own version rather than following this pointer, so editing an agent does
     * not rewrite what a trace from last week says the agent was told to do.
     */
    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "owner_id")
    private UUID ownerId;

    /** The ElevenLabs voice this agent speaks with. Null means the browser's own voice is used. */
    @Column(name = "voice_id")
    private String voiceId;

    /**
     * Whether this is the workspace's General Employee, the agent that takes a request no
     * specialist fits. Found by this flag, never by its key, so a custom agent someone keyed
     * "general" is never mistaken for it. At most one per workspace (V8).
     */
    @Column(name = "is_fallback", nullable = false)
    private boolean fallback;

    public boolean isActive() {
        return "active".equals(status);
    }

    /** Archived: takes no work, keeps its history, can be restored. */
    public boolean isRetired() {
        return "retired".equals(status);
    }

    public boolean isFallback() {
        return fallback;
    }

    public void setFallback(boolean fallback) {
        this.fallback = fallback;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(UUID currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public void setVoiceId(String voiceId) {
        this.voiceId = voiceId;
    }
}
