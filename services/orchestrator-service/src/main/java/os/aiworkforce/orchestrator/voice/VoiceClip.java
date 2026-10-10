// @find: voice clip entity, spoken clip, audio note, voice note, run audio, voice_clips table, VoiceClip
// @what: JPA entity for a spoken audio clip produced by a run.
// @flow: Stored in voice_clips; created by VoiceClipService
package os.aiworkforce.orchestrator.voice;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * One piece of audio ElevenLabs produced: the reply a person hears back in Chat, or the voice
 * note an agent's own tool call wrote as a script.
 *
 * <p>Modelled directly, the way {@code RunStep} is, rather than through {@code OrgScopedEntity}:
 * a clip is written once and never edited, so it carries none of optimistic locking, an updater
 * or a last-modified timestamp - only when it was made and by what.
 */
@Entity
@Table(name = "voice_clips")
public class VoiceClip {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "voice_id", nullable = false)
    private String voiceId;

    @Column(name = "content_type", nullable = false)
    private String contentType = "audio/mpeg";

    @Column(nullable = false)
    private byte[] audio;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public static VoiceClip of(
            UUID orgId, UUID runId, UUID agentId, String text, String voiceId, String contentType, byte[] audio) {
        VoiceClip clip = new VoiceClip();
        clip.orgId = orgId;
        clip.runId = runId;
        clip.agentId = agentId;
        clip.text = text;
        clip.voiceId = voiceId;
        clip.contentType = contentType == null ? "audio/mpeg" : contentType;
        clip.audio = audio;
        return clip;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getText() {
        return text;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getAudio() {
        return audio;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
