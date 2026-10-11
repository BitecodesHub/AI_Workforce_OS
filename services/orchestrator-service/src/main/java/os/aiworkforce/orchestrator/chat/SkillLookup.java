// @find: skill lookup, resolve skill for chat, skill playbook for a message, default agent of a skill
// @what: How the chat coordinator finds the skill a message names, without depending on how skills are stored.
// @flow: Implemented by skill.SkillChatLookup; called by CoordinatorService when a message carries a skillId.
package os.aiworkforce.orchestrator.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finds a workspace's skill for a message that names one. */
public interface SkillLookup {

    /**
     * A skill as a message uses it.
     *
     * @param defaultAgentId the agent that takes the message when nobody is mentioned, or null
     * @param requiredConnectors connector server ids the playbook needs, for the agent's information
     */
    record ResolvedSkill(
            UUID id, String name, String instructions, UUID defaultAgentId, List<String> requiredConnectors) {

        public ResolvedSkill {
            requiredConnectors = requiredConnectors == null ? List.of() : List.copyOf(requiredConnectors);
        }
    }

    /** The skill, or empty when this workspace has none with that id. */
    Optional<ResolvedSkill> find(UUID orgId, UUID skillId);
}
