// @find: built in skills, ready made skills, seed skills, default playbooks, weekly status report, triage support inbox, code review a pr, summarise a document, draft a customer reply, BuiltInSkills
// @what: The five ready-made skills every workspace is given once, with their instructions and the agent key each prefers.
// @flow: Read by SkillService when it seeds a workspace on its first list
package os.aiworkforce.orchestrator.skill;

import java.util.List;

/**
 * The ready-made skills, defined as data.
 *
 * <p>Each names the agent key it would prefer to take the message ({@code agentKey}); the seeder
 * looks that key up in the workspace and leaves the default empty when no active agent has it, so
 * a workspace without a support agent simply routes as usual.
 */
final class BuiltInSkills {

    /** One ready-made skill before it is written for a workspace. */
    record Definition(
            String name, String description, String instructions, String agentKey, List<String> requiredConnectors) {}

    static final List<Definition> ALL = List.of(
            new Definition(
                    "Weekly status report",
                    "Turn the week's notes and updates into a short status report for your team or manager.",
                    """
                    Write a weekly status report from the material I give you (notes, task lists, messages, \
                    figures) and anything you can find in the workspace.

                    ## Steps
                    1. Work out the reporting week and who the report is for. If I have not said, ask once, \
                    then assume the current week and a manager audience.
                    2. Group the work into what was finished, what is in progress and what is blocked.
                    3. For each blocked item, name the blocker and who can unblock it.
                    4. Pick out the two or three results that matter most to the reader.

                    ## Output format
                    **Summary** - two or three sentences on how the week went overall.
                    **Done** - bullet list, one line each, with the outcome rather than the activity.
                    **In progress** - bullet list with the expected finish date where known.
                    **Blocked or at risk** - bullet list with the blocker and the help needed.
                    **Next week** - the three to five priorities.

                    ## Rules
                    - Keep it under 300 words. Plain, confident language; no filler.
                    - Never invent progress, dates or numbers. If something is unclear, mark it \
                    "to confirm" rather than guessing.
                    - If I give you no material at all, ask what happened this week before writing anything.
                    """,
                    null,
                    List.of()),
            new Definition(
                    "Triage support inbox",
                    "Sort new support emails by urgency and topic, and suggest the next step for each.",
                    """
                    Triage the unread or recent support messages in the inbox (or the messages I paste).

                    ## Steps
                    1. Read each message and work out what the customer actually needs.
                    2. Give each one a priority:
                       - **Urgent** - service down, data loss, security concern, payment failure, or a \
                    customer clearly at risk of leaving.
                       - **High** - a feature broken for this customer, a deadline mentioned.
                       - **Normal** - questions, how-to requests, minor issues.
                       - **Low** - feedback, newsletters, anything that needs no reply.
                    3. Tag each with a topic: billing, bug, account access, how-to, feature request or other.
                    4. Suggest the next step: reply with an answer, escalate to a named team, or close.

                    ## Output format
                    A table with the columns: Priority, From, Subject, Topic, Next step. Sort urgent first. \
                    Below the table, list any message you could not classify and why.

                    ## Rules
                    - Do not send, archive or delete anything; this is a recommendation only.
                    - Do not copy card numbers, passwords or other sensitive details into the summary.
                    - If you cannot reach the inbox, say so and ask me to paste the messages instead.
                    - Keep each "next step" to one short sentence.
                    """,
                    "support",
                    List.of("gmail")),
            new Definition(
                    "Code review a PR",
                    "Review a pull request for bugs, risks and clarity, and give clear, prioritised feedback.",
                    """
                    Review the pull request I name (a link, or a repository and number).

                    ## Steps
                    1. Read the description and the linked issue to understand what the change is meant to do. \
                    If there is no description, say so and infer the intent from the diff.
                    2. Read the whole diff, then the surrounding code where a change depends on it.
                    3. Look, in order of importance, for: correctness bugs and missed edge cases; security \
                    problems (injection, secrets, missing permission checks); data loss or migration risks; \
                    missing or weak tests; performance traps; then naming and readability.
                    4. Check that tests cover the behaviour that changed.

                    ## Output format
                    **Verdict** - approve, approve with comments, or request changes, with one sentence why.
                    **Must fix** - numbered, each with file and line, the problem, and a suggested fix.
                    **Should fix** - same format, for things worth changing but not blocking.
                    **Nice to have** - brief.
                    **What is good** - one or two specific points.

                    ## Rules
                    - Be specific and kind; critique the code, never the author.
                    - Do not post comments, approve or merge on GitHub unless I ask you to.
                    - If you cannot open the pull request, ask me for the repository, number or diff.
                    """,
                    "engineering-manager",
                    List.of("github")),
            new Definition(
                    "Summarise a document",
                    "Get the key points, decisions and actions from a long document in a few lines.",
                    """
                    Summarise the document I attach, link or paste.

                    ## Steps
                    1. Identify what kind of document it is (report, contract, meeting notes, proposal, policy) \
                    and who wrote it for whom.
                    2. Pull out the main argument or purpose, the key facts and figures, any decisions made, \
                    and any actions with owners and dates.
                    3. Note anything that looks risky, contradictory or missing.

                    ## Output format
                    **In one sentence** - what the document is and why it matters.
                    **Key points** - five to eight bullets, most important first.
                    **Decisions** - bullets, or "None recorded".
                    **Actions** - who, what and by when, or "None recorded".
                    **Worth a closer look** - risks, gaps or open questions.

                    ## Rules
                    - Stay faithful to the source: no opinions or facts that are not in it.
                    - Quote exact figures, dates and names; mark anything ambiguous as unclear.
                    - Keep the whole summary under 250 words unless I ask for more detail.
                    - If no document is attached or I have not said which one, ask for it before starting.
                    - If I mention an audience (board, customer, new starter), adjust the wording for them.
                    """,
                    null,
                    List.of()),
            new Definition(
                    "Draft a customer reply",
                    "Write a clear, friendly reply to a customer message, ready for you to check and send.",
                    """
                    Draft a reply to the customer message I paste or point to.

                    ## Steps
                    1. Work out what the customer is asking for and how they feel (confused, frustrated, \
                    happy, in a hurry).
                    2. Find the answer from the workspace's knowledge, the conversation so far, or what I tell \
                    you. If you do not know a fact the reply depends on (a refund policy, a delivery date, \
                    an account detail), ask me rather than guessing.
                    3. Write the reply.

                    ## Output format
                    **Subject** - only when replying by email.
                    **Reply** - the message itself, ready to send:
                    - Greet the customer by name if it is known.
                    - Acknowledge the issue in one sentence; apologise only when we got something wrong.
                    - Give the answer or the next step plainly, with any dates or links.
                    - Close with what happens next and a friendly sign-off.
                    **Notes for you** - anything I should check before sending.

                    ## Rules
                    - Warm, professional and concise: usually under 150 words.
                    - No jargon, no blame, no promises we cannot keep.
                    - Never send the reply yourself; this is a draft for me to review.
                    - Never include passwords, full card numbers or other sensitive data.
                    """,
                    "support",
                    List.of()));

    private BuiltInSkills() {}
}
