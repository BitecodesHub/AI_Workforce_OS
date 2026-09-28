package os.aiworkforce.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

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

    public boolean isActive() {
        return "active".equals(status);
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
