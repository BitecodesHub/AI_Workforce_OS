// @find: agent schedule tool, schedule.create, schedule__create, agent creates a schedule, do something later, remind later, recurring task from a run, AgentScheduleTool
// @what: The internal tool an agent calls to set up work for later (once or recurring) when the person asks for it, with a plain-words confirmation.
// @flow: Offered and run by AgentRunner; saves through ScheduleRequests/ScheduleService
package os.aiworkforce.orchestrator.schedule;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

/**
 * Lets an agent that is asked to do something later - "every Friday send me the pipeline summary",
 * "check again tomorrow at 9am" - set that up itself, rather than telling the person to.
 *
 * <p>Flow control inside the platform rather than an integration, like the ask tool: no server
 * runs it and no grant is needed. The schedule runs as the person the run is for, exactly as if
 * they had created it in chat, and every change to it afterwards follows the schedule rules. A run
 * that a schedule started may not create another one, so a schedule can never multiply itself.
 */
@Component
public class AgentScheduleTool {

    /** Sent to providers as {@code schedule__create}. */
    public static final String NAME = "schedule.create";

    static final String DESCRIPTION = "Set up work to happen later, once or on a repeating schedule, when the person asks "
            + "for something to be done at a later time or regularly (for example 'every Monday at 9am summarise "
            + "competitor news' or 'remind HR tomorrow at 3pm to send onboarding emails'). Do not use it for work you "
            + "can do now. 'when' is the timing in plain words, such as 'every weekday at 9am', 'every Monday at "
            + "9am', 'every 2 hours', 'on the 1st of every month at 9am', 'tomorrow at 3pm', 'in 2 hours' or 'on 1 "
            + "October at 9am'; it is read in the workspace's time zone. 'instruction' is exactly what should be done "
            + "each time, written so it makes sense on its own. 'agent' is the name of the colleague who should do "
            + "it; leave it out for yourself. The result confirms the schedule and its next run: tell the person "
            + "that in your answer.";

    static final String SCHEMA =
            """
            {"type":"object","required":["when","instruction"],"properties":{
             "when":{"type":"string","description":"The timing in plain words, for example every Monday at 9am or tomorrow at 3pm."},
             "instruction":{"type":"string","description":"What should be done each time, complete on its own."},
             "agent":{"type":"string","description":"The colleague who should do it; leave out for yourself."}}}
            """
                    .strip();

    public static final ToolSpec SPEC = new ToolSpec(NAME, DESCRIPTION, SCHEMA, ToolSpec.SideEffect.WRITE);

    static final int MAX_WHEN = 200;
    static final int MAX_INSTRUCTION = 10_000;

    /** The model's arguments cannot be used, or the schedule cannot be made; the message is for the model. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    /** A schedule made, with the sentence that confirms it. */
    public record Made(Schedule schedule, String agentName, String sentence) {}

    private final ScheduleRequests requests;
    private final Agents agents;
    private final ObjectMapper json;

    public AgentScheduleTool(ScheduleRequests requests, Agents agents, ObjectMapper json) {
        this.requests = requests;
        this.agents = agents;
        this.json = json;
    }

    // @find: create schedule from agent tool call
    /**
     * Makes the schedule a model asked for.
     *
     * @param self the agent calling the tool, which does the work unless another is named
     * @param requester the person the run is for, who the schedule then runs as
     * @param fromSchedule whether the calling run was itself started by a schedule
     */
    public Made create(UUID orgId, Agent self, UUID requester, boolean fromSchedule, String argumentsJson)
            throws Refused {
        if (fromSchedule) {
            throw new Refused("A run started by a schedule cannot create another schedule. Say in your answer what "
                    + "should be scheduled, and the person can set it up.");
        }
        if (requester == null) {
            throw new Refused("This run is not for a person, so there is nobody a schedule could run as. Say in your "
                    + "answer what should be scheduled instead.");
        }
        JsonNode root;
        try {
            root = json.readTree(argumentsJson == null ? "" : argumentsJson);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new Refused("The arguments were not valid JSON. Send an object with when and instruction.");
        }
        String when = text(root, "when");
        String instruction = text(root, "instruction");
        if (when == null) {
            throw new Refused("Say when, in plain words, for example every Monday at 9am or tomorrow at 3pm.");
        }
        if (instruction == null) {
            throw new Refused("Say what should be done each time in instruction.");
        }
        if (when.length() > MAX_WHEN || instruction.length() > MAX_INSTRUCTION) {
            throw new Refused("Keep when under 200 characters and the instruction under 10,000.");
        }
        Agent agent = resolveAgent(orgId, self, text(root, "agent"));
        Actor owner = Actor.user(requester.toString(), orgId.toString(), null, Set.of(), 0L);
        try {
            ScheduleRequests.Confirmation saved =
                    RequestContext.as(owner, () -> requests.create(orgId, agent, instruction, when));
            return new Made(saved.schedule(), agent.getName(), saved.content());
        } catch (ApiException refused) {
            String reason = refused.getMessage();
            Object examples = refused.details() == null ? null : refused.details().get("examples");
            throw new Refused(reason + (examples instanceof List<?> list && !list.isEmpty()
                    ? " Phrases that work: " + String.join("; ", list.stream().map(String::valueOf).toList()) + "."
                    : ""));
        }
    }

    /** The text the model reads back: the confirmation and the schedule's id. */
    public static String modelText(Made made) {
        return made.sentence() + " (Schedule " + made.schedule().getId() + ".)";
    }

    private Agent resolveAgent(UUID orgId, Agent self, String wanted) throws Refused {
        if (wanted == null) {
            return self;
        }
        String normalised = normalise(wanted.replaceFirst("^@", ""));
        if (normalised.equals(normalise(self.getName())) || normalised.equals(normalise(self.getKey()))) {
            return self;
        }
        List<Agent> all = agents.findByOrgIdOrderByName(orgId);
        for (Agent agent : all) {
            if (normalised.equals(normalise(agent.getName())) || normalised.equals(normalise(agent.getKey()))) {
                if (!agent.isActive()) {
                    throw new Refused(agent.getName() + " is paused, so it cannot be given scheduled work.");
                }
                return agent;
            }
        }
        throw new Refused("No colleague is called " + wanted + ". The agents here are: "
                + String.join(", ", all.stream().filter(Agent::isActive).map(Agent::getName).toList()) + ".");
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "");
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
