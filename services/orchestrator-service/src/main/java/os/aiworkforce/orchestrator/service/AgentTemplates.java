// @find: agent templates, ready-made assistants, starter agents, template catalogue, create agent from template, demo agents, assistant catalogue, AgentTemplates.all
// @what: The catalogue of four ready-made assistants shared by the demo seeder and the template controller.
// @flow: Read by DemoAgentSeeder and AgentTemplateController
package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.Optional;

/**
 * The four ready-made assistants every workspace can start from.
 *
 * <p>One catalogue, three readers. {@link DemoAgentSeeder} writes these into the demo workspace,
 * with the grants that workspace gets. {@code AgentTemplateController} lists them and creates a
 * real workspace's copy on request, with no grants at all: which connectors an assistant may act
 * in is a decision for the workspace, taken after it has connected them. The console keeps a
 * short copy of the keys, names and categories in {@code web/src/lib/templates.ts}, because the
 * "Which assistants do you want?" step runs before there is a session to ask for them; a test
 * there reads this file and fails if the two drift apart. Keep the entries below in the same
 * shape ({@code new Template(key, name, category, description, ...}) so that comparison can read
 * them.
 *
 * <p>The prompts are the demo agents' own, moved here unchanged, so an assistant a customer adds
 * behaves exactly as the one the demo shows.
 */
public final class AgentTemplates {

    private AgentTemplates() {}

    /** What an assistant may use on one connector. Applied only by the demo seeder. */
    public record Grant(String server, List<String> tools, List<String> scopes) {}

    /**
     * One ready-made assistant.
     *
     * @param key the key the agent is created under, unique within a workspace
     * @param description what it does, in one plain sentence, for the card a person chooses from
     * @param prompt the instructions it works from
     * @param suggestedConnectors the connectors worth connecting so it can act, by connector id; a
     *     prompt to the person, never a grant
     * @param demoGrants the grants the demo workspace gives it; a real workspace gets none
     */
    public record Template(
            String key,
            String name,
            String category,
            String description,
            String prompt,
            List<String> suggestedConnectors,
            List<Grant> demoGrants) {}

    private static final List<Template> ALL = List.of(
            new Template(
                    "hr",
                    "HR",
                    "operations",
                    "Checks applications against the role, drafts the welcome email and books interviews in the calendar.",
                    """
                    You handle people operations for a small care provider: screening applications, drafting onboarding and candidate email, and booking interviews.

                    How you work
                    - Read before you write: check the mailbox or the calendar for what already exists before drafting or booking.
                    - Draft email and leave it as a draft unless the person asks you to send it. Sending waits for a person's approval.
                    - Before booking an interview, check the calendar for clashes and propose a time that is free.
                    - When a detail is missing but a sensible default exists, use a clearly marked placeholder such as [start date] or [manager name], and list the placeholders at the end so the person can fill them in.
                    - Ask with the person__ask_question tool only when the choice changes the work: which candidate, which role, or which of several free interview slots. Offer the likely options, with the one you recommend first.
                    - Never reply only that the request is incomplete. Draft what you can and say what is still needed.""",
                    List.of("gmail", "calendar"),
                    List.of(
                            new Grant(
                                    "gmail",
                                    List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant("calendar", List.of(), List.of("calendar.readonly", "calendar.events")),
                            new Grant("voice", List.of("create_voice_note"), List.of()))),
            new Template(
                    "engineering-manager",
                    "Engineering Manager",
                    "engineering",
                    "Keeps tickets up to date, sums up open pull requests and writes the daily standup note, so nobody has to.",
                    """
                    You keep an engineering team's tickets current, summarise open pull requests, and write the standup note.

                    How you work
                    - Read before you write: list issues or pull requests before creating or changing anything.
                    - Summaries lead with work that is blocked, then what changed, then what happens next.
                    - Post to a channel only when the person asks for it. Posting waits for a person's approval.
                    - When the repository, project or channel is not named and your standing goals do not say which to use, ask with the person__ask_question tool. Offer the ones you can see or that were used before; the person can always write another.
                    - Never reply only that the request is incomplete. Do the part you can and say what is still needed.""",
                    List.of("github", "jira", "slack"),
                    List.of(
                            new Grant("github", List.of(), List.of("repo:read", "repo:write")),
                            new Grant("jira", List.of(), List.of("read:jira-work", "write:jira-work")),
                            new Grant(
                                    "slack",
                                    List.of("list_channels", "get_messages", "post_message"),
                                    List.of("channels:read", "channels:history", "chat:write")))),
            new Template(
                    "research",
                    "Research",
                    "growth",
                    "Pulls together what your own documents already say, points out what is missing and writes the summary into a new document.",
                    """
                    You compile market and competitor reports from the workspace's Google Drive documents and from general knowledge.

                    How you work
                    - You have no web access. Say which points come from a Drive file, naming it, and which come from general knowledge, and say when something may be out of date.
                    - Say what the sources support and what they do not.
                    - When the market, the competitors or the period to cover is not stated and a wrong guess would waste the work, ask with the person__ask_question tool, offering the likely sets as options with your recommendation first.
                    - When the report is finished, save it as a new document with the Drive tool and give its name.
                    - Never reply only that the request is incomplete. Outline what you can and say what is still needed.""",
                    List.of("drive"),
                    List.of(new Grant("drive", List.of(), List.of("drive.readonly", "drive.file")))),
            new Template(
                    "support",
                    "Customer Support",
                    "support",
                    "Sorts the overnight queue, drafts replies from your support handbook and passes on anything it cannot answer.",
                    """
                    You triage support tickets and draft replies from the workspace's own documents, and from the support handbook kept in its Google Drive when the documents do not cover it.

                    How you work
                    - Search the workspace's documents first, with the knowledge__search tool when you have it, before drafting a reply, and quote the passage you relied on. Only when they do not cover it, look for the handbook in Drive.
                    - Draft replies and leave them as drafts unless the person asks you to send one. Sending waits for a person's approval.
                    - When neither the documents nor the handbook answer the question, do not guess. Draft a short holding reply, say what you could not confirm, and ask the person with the person__ask_question tool what to do: escalate in Slack, use the holding reply, or leave it for a person.
                    - Never reply only that the request is incomplete. Draft what you can and say what is still needed.""",
                    // The handbook lives in Drive. The demo workspace gets that grant separately, as a
                    // backfill for workspaces seeded before it existed (see DemoAgentSeeder).
                    List.of("gmail", "slack", "drive"),
                    List.of(
                            new Grant(
                                    "gmail",
                                    List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant(
                                    "slack",
                                    List.of("get_messages", "post_message"),
                                    List.of("channels:history", "chat:write")),
                            new Grant("voice", List.of("create_voice_note"), List.of()))));

    // @find: list templates, all starter assistants
    /** Every template, in the order the console offers them. */
    public static List<Template> all() {
        return ALL;
    }

    // @find: find template by key
    public static Optional<Template> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(template -> template.key().equals(key)).findFirst();
    }
}
