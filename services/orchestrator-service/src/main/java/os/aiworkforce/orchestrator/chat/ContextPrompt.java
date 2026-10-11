// @find: context prompt, skill instructions in prompt, playbook block, repository block, chosen documents block, apply skill to run, attached repo instructions
// @what: Writes what a message's "+" attachments mean for the agent that takes it: the skill to follow, the repository to work on, the documents chosen.
// @flow: Called by CoordinatorService when it builds the tasks for a message with a MessageContext.
package os.aiworkforce.orchestrator.chat;

import java.util.List;
import java.util.StringJoiner;

/**
 * The block added to the instruction of every task started from a message with context.
 *
 * <p>A skill's playbook is written by a member who may manage agents, so it is given as
 * instructions; it follows the person's own request, which still decides what is wanted. The
 * repository and the chosen documents are facts about the request: where to look, not what to do.
 */
public final class ContextPrompt {

    /** Not instantiable; every member is static. */
    private ContextPrompt() {}

    /** Longest playbook text handed to a model (the store allows 20,000), leaving room in the instruction for the thread. */
    static final int MAX_SKILL_CHARS = 12_000;

    // @find: build context block for a task instruction
    /**
     * The block, starting with a blank line, or an empty string when the context adds nothing.
     *
     * <p>Called by {@code CoordinatorService} for each task it creates from a message with context.
     * Up to three sections are written, in this order: the skill playbook, cut to {@link
     * #MAX_SKILL_CHARS} and wrapped in a {@code skill} tag with the rule that the request wins; the
     * repository, with a read-only warning; and the chosen documents. Pure; no I/O.
     */
    public static String block(MessageContext context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (context.skill() != null) {
            SkillLookup.ResolvedSkill skill = context.skill();
            String instructions = skill.instructions().length() > MAX_SKILL_CHARS
                    ? skill.instructions().substring(0, MAX_SKILL_CHARS)
                    : skill.instructions();
            out.append("\n\n## Skill to follow: ").append(skill.name()).append('\n');
            out.append("The person chose this workspace playbook for the request above. Follow its steps and its output ")
                    .append("format, applied to what the person asked. Where the request and the playbook differ, ")
                    .append("the request wins.\n");
            out.append("<skill name=\"").append(attribute(skill.name())).append("\">\n")
                    .append(instructions.strip())
                    .append("\n</skill>");
            if (!skill.requiredConnectors().isEmpty()) {
                out.append("\nIt expects these connectors: ")
                        .append(String.join(", ", skill.requiredConnectors()))
                        .append(". If one is not available to you, say what could not be done rather than inventing it.");
            }
        }
        if (context.repo() != null) {
            MessageContext.RepoRef repo = context.repo();
            out.append("\n\n## Repository\n");
            out.append("This request is about the GitHub repository ").append(repo.fullName());
            out.append(repo.branch() == null ? " (its default branch)" : " on branch " + repo.branch());
            if (repo.path() != null) {
                out.append(", in ").append(repo.path());
            }
            out.append(". Work on that repository: read it with the GitHub tools, passing repo \"")
                    .append(repo.fullName())
                    .append('"');
            if (repo.branch() != null) {
                out.append(" and ref \"").append(repo.branch()).append('"');
            }
            out.append(". Read before you state anything about its code. Do not change it - no commits, branches, ")
                    .append("issues, comments, pull requests or merges - unless the person asked for that change. ")
                    .append("If you have no GitHub tools, say so plainly instead of guessing what it contains.");
        }
        if (!context.knowledge().isEmpty()) {
            out.append("\n\n## Documents the person chose\n");
            out.append("Ground the answer in these workspace documents first: ");
            StringJoiner names = new StringJoiner(", ");
            List<String> titles = context.titles();
            titles.forEach(title -> names.add("\"" + title + "\""));
            out.append(titles.isEmpty() ? "the sources attached to the message" : names.toString());
            out.append(". Passages from them are given above when any matched. If you search the workspace's ")
                    .append("documents, search these first, and say plainly when they do not cover something.");
        }
        return out.toString();
    }

    /** Escapes a value for use inside a quoted tag attribute. */
    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
