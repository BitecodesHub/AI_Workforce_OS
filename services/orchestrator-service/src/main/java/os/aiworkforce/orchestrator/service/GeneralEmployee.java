package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * The workspace's employee of last resort: the agent that takes a request no specialist covers.
 *
 * <p>Every workspace gets exactly one, found by the {@code agents.is_fallback} flag rather than by
 * a key a person could type. That keeps the fallback safe from a custom agent someone happens to
 * key {@code "general"} - such an agent stays an ordinary specialist and is scored like one - and
 * it means a workspace can rename or retire its General Employee without breaking the routing that
 * finds it.
 *
 * <p>{@link #ensure(UUID)} creates the agent lazily, the first time a workspace is touched by chat,
 * the agents list or the orchestrator board, rather than at signup for every workspace that may
 * never use chat. It never throws into its caller: a workspace that could not get a General
 * Employee this time still gets everything else it asked for, and the next call tries again.
 */
@Component
public class GeneralEmployee {

    private static final Logger log = LoggerFactory.getLogger(GeneralEmployee.class);

    /** The key used when it is free. A workspace that already has a custom agent keyed "general" gets FALLBACK_KEYS[1]. */
    static final List<String> FALLBACK_KEYS = List.of("general", "general-employee", "general-employee-2");

    public static final String NAME = "General Employee";
    public static final String CATEGORY = "operations";
    static final int MAX_STEPS = 10;

    static final String PROMPT =
            """
            You are the workspace's General Employee: you take any request no specialist covers, from questions and explanations to drafting, planning and analysis.

            What good work looks like
            - Do the work that was asked, completely, in this reply. Lead with the answer or the finished piece, then the detail a busy person needs. Match the length to the request.
            - Use headings, lists or a table only when the content has that shape. Write in plain English, and in the person's own language when they write in another one.
            - Show your working for calculations and estimates so the person can check them.

            When something is missing
            - Most requests can be done as written. When a detail is missing but a sensible default exists (length, tone, format, audience), choose the default, do the work, and end with one line naming the assumption.
            - A greeting, a thank-you, a short question or a request you can do as written is never a reason to ask: answer it or do it, and do not ask on your first step when the request can be done as it stands.
            - Ask first only when you truly cannot proceed with a reasonable default and the right answer depends on something only the person knows, where a wrong guess would waste their time: which of several things they mean, who it is for, or a date, budget or name that changes the result.
            - To ask, call the person__ask_question tool; never ask in your reply text. Keep it to what matters: a header of one or two words, the question, and two to four options, each with a short label and a one-line description. Put the option you recommend first. Set multiSelect only when several options can apply together. Do not add an "Other" option; the person can always write their own answer.
            - Ask at most two times in one piece of work. When the answer arrives, continue from where you stopped, and do not ask the same thing again.
            - Never reply that a request is incomplete, unclear or needs more detail. Either do the work with a stated assumption, or ask one specific question with options.

            What you can and cannot do
            - You have no email, calendar, chat, code, ticket or file tools and no web access. Never claim to have sent, booked, posted or searched the web, or to have looked anything up except in the workspace's documents when you were given them.
            - When the request is really a specialist's job, do the part you can (draft the text, outline the steps) and name the colleague who can finish it, so the person can mention them with @. The colleagues are listed after the request when there are any.
            - You do not know this workspace's own policies, people, customers or figures unless they are in the request, in work handed to you, or in the reference material or documents you are given. When the knowledge__search tool is offered, search the workspace's documents before you state a policy, price or process, and say so plainly when they do not cover it rather than inventing it.
            - When a fact may have changed since your training, or you are unsure, say so in the same sentence.
            - For clinical, legal, employment or financial questions, give clear general information, say that it is general, and name who in the organisation should confirm it before anyone acts.
            - When a colleague hands you earlier work, build on it rather than starting again.
            """;

    private final Agents agents;
    private final AgentVersions versions;
    private final TransactionTemplate requiresNew;
    private final Set<UUID> ensured = ConcurrentHashMap.newKeySet();

    public GeneralEmployee(Agents agents, AgentVersions versions, PlatformTransactionManager transactionManager) {
        this.agents = agents;
        this.versions = versions;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Creates the workspace's General Employee when it has none, choosing a free key from
     * {@link #FALLBACK_KEYS}. Does nothing when a flagged agent already exists, whatever its
     * status or key - a paused or retired General stays paused or retired. Never throws.
     */
    public void ensure(UUID orgId) {
        if (orgId == null || ensured.contains(orgId)) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> {
                if (agents.findByOrgIdAndFallbackTrue(orgId).isPresent()) {
                    return;
                }
                String key = FALLBACK_KEYS.stream()
                        .filter(candidate ->
                                agents.findByOrgIdAndKey(orgId, candidate).isEmpty())
                        .findFirst()
                        .orElse(null);
                if (key == null) {
                    log.warn("Every General Employee key is taken in workspace {}", orgId);
                    return;
                }
                Agent agent = new Agent();
                agent.setId(UuidV7.generate());
                agent.setOrgId(orgId);
                agent.setKey(key);
                agent.setName(NAME);
                agent.setCategory(CATEGORY);
                agent.setFallback(true);
                agents.save(agent);

                AgentVersion version = new AgentVersion();
                version.setId(UuidV7.generate());
                version.setAgentId(agent.getId());
                version.setOrgId(orgId);
                version.setRevision(1);
                version.setSystemPrompt(PROMPT);
                version.setMaxSteps(MAX_STEPS);
                version.setCreatedBy("system");
                versions.save(version);

                agent.setCurrentVersionId(version.getId());
                agents.save(agent);
            });
            ensured.add(orgId);
        } catch (DataIntegrityViolationException raced) {
            // agents_one_fallback (V8) or agents_org_key_unique (V1:95): another request created it first.
            ensured.add(orgId);
        } catch (RuntimeException e) {
            log.warn("Could not ensure General Employee in workspace {}: {}", orgId, e.getMessage());
        }
    }

    /** The active General Employee among the workspace's agents, if any. */
    public Optional<Agent> activeIn(List<Agent> workspaceAgents) {
        return workspaceAgents.stream()
                .filter(GeneralEmployee::isFallback)
                .filter(Agent::isActive)
                .findFirst();
    }

    public static boolean isFallback(Agent agent) {
        return agent != null && agent.isFallback();
    }
}
