// @find: skill chat lookup, resolve skill for chat message, SkillLookup implementation
// @what: Gives the chat coordinator a workspace's skill by id, as the playbook a message follows.
// @flow: Implements chat.SkillLookup over SkillService.find; injected into CoordinatorService.
package os.aiworkforce.orchestrator.skill;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.chat.SkillLookup;

/** The skills a chat message may name, read from the workspace's own. */
@Component
public class SkillChatLookup implements SkillLookup {

    private final SkillService skills;

    /** Creates the lookup over the skill service. */
    public SkillChatLookup(SkillService skills) {
        this.skills = skills;
    }

    /** Returns the skill as the plain playbook the chat coordinator needs (name, instructions, preferred agent and required connectors), or empty when the workspace has no such skill. */
    @Override
    public Optional<ResolvedSkill> find(UUID orgId, UUID skillId) {
        return skills.find(orgId, skillId)
                .map(skill -> new ResolvedSkill(
                        skill.getId(),
                        skill.getName(),
                        skill.getInstructions(),
                        skill.getDefaultAgentId(),
                        skill.getRequiredConnectors()));
    }
}
