// @find: ask person tool, person.ask_question, ask a question, run asks the person, clarifying question, multiple choice options, question parser, person__ask_question, agent asks human
// @what: Defines the tool an agent uses to stop and ask the person a question with up to four multiple-choice questions, and validates the model's arguments.
// @flow: Called by AgentRunner when the model calls the ask tool; the parsed Ask goes to QuestionService.raise
package os.aiworkforce.orchestrator.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import os.aiworkforce.llm.model.ToolSpec;

/**
 * The tool every run a person can answer is offered: stop and ask the person a question.
 *
 * <p>It is flow control rather than an integration, so no server runs it and no grant is needed.
 * {@link AgentRunner} recognises the name before it looks for a server, parks the run, and
 * delivers the answer later as this call's result.
 *
 * <p>The schema the model sees is deliberately loose, because Gemini and some OpenAI-compatible
 * routes reject a schema using keywords outside a small common subset. The strictness lives here
 * instead: {@link #parse} checks every limit, repairs what can be repaired without changing the
 * meaning (a long header, a long description, a "(Recommended)" suffix), and otherwise refuses
 * with a sentence the model can act on.
 */
@Component
public class AskPersonTool {

    /** Sent to providers as {@code person__ask_question}. */
    public static final String NAME = "person.ask_question";

    static final String DESCRIPTION = "Ask the person you are working for a question, and wait for their answer. "
            + "Use it only when you truly cannot proceed with a reasonable default and the work cannot be done well "
            + "without information only they have, such as which of several things they mean, who it is for, or a "
            + "date, budget or name that changes the result. Never use it for a greeting, a thank-you or a request "
            + "you can answer or do as written, and not on your first step when the request can be done as it stands: "
            + "choose the default, do the work and name the assumption instead. Ask one "
            + "to four short questions together, usually one. Give each two to four concrete options, each with a "
            + "short label and a one-line description, and put the option you recommend first. Set multiSelect when "
            + "several options can apply together. Do not add an Other option; the person can always write their "
            + "own answer. Do not ask for permission to act, because approvals are handled separately, and do not "
            + "ask what the instruction or an earlier answer already says. The run pauses until the person "
            + "answers, and their answer is returned as this tool's result.";

    static final String SCHEMA =
            """
            {"type":"object","required":["questions"],"properties":{
             "questions":{"type":"array","minItems":1,"maxItems":4,"description":"One to four questions to ask together. Usually one.",
              "items":{"type":"object","required":["header","question","options"],"properties":{
               "header":{"type":"string","maxLength":16,"description":"One to three words, shown as a chip, for example Date range."},
               "question":{"type":"string","maxLength":300,"description":"The whole question, ending with a question mark."},
               "multiSelect":{"type":"boolean","description":"True when several options can be chosen together."},
               "options":{"type":"array","minItems":2,"maxItems":4,"items":{"type":"object","required":["label","description"],
                 "properties":{"label":{"type":"string","maxLength":60},"description":{"type":"string","maxLength":160}}}},
               "recommended":{"type":"integer","minimum":0,"maximum":3,"description":"Index of the option you recommend, if any."}}}}}}
            """
                    .strip();

    public static final ToolSpec SPEC = new ToolSpec(NAME, DESCRIPTION, SCHEMA, ToolSpec.SideEffect.READ);

    static final int MAX_QUESTIONS = 4;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 4;
    static final int MAX_HEADER = 16;
    static final int MAX_QUESTION = 300;
    static final int MAX_LABEL = 60;
    static final int MAX_DESCRIPTION = 160;
    private static final String RECOMMENDED_SUFFIX = " (recommended)";

    /** One choice. {@code recommended} is true for at most one option, which is always first. */
    public record Option(String label, String description, boolean recommended) {}

    /** One question, with its id {@code q1} to {@code q4} in the order it was asked. */
    public record Question(String id, String header, String question, boolean multiSelect, List<Option> options) {}

    /** What the model asked, normalised. */
    public record Ask(List<Question> questions) {}

    /** The model's arguments cannot be used; the message is written for the model to act on. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    private final ObjectMapper json;

    public AskPersonTool(ObjectMapper json) {
        this.json = json;
    }

    // @find: parse ask question arguments, validate questions and options, normalise model input
    /** Parses and normalises the model's arguments, or throws Invalid with a sentence the model can act on. */
    public Ask parse(String argumentsJson) throws Invalid {
        JsonNode root;
        try {
            root = json.readTree(argumentsJson == null ? "" : argumentsJson);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new Invalid("The arguments were not valid JSON. Send an object with a questions array.");
        }

        List<JsonNode> entries = new ArrayList<>();
        JsonNode questions = root.get("questions");
        if (questions == null && root.has("question") && root.has("options")) {
            // A common shortcut: one question sent as the whole object. Read as a list of one.
            entries.add(root);
        } else if (questions != null && questions.isArray()) {
            questions.forEach(entries::add);
        }
        if (entries.isEmpty() || entries.size() > MAX_QUESTIONS) {
            throw new Invalid("Ask between one and four questions.");
        }

        List<Question> parsed = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            parsed.add(parseQuestion("q" + (index + 1), entries.get(index)));
        }
        return new Ask(List.copyOf(parsed));
    }

    private static Question parseQuestion(String id, JsonNode node) throws Invalid {
        if (!node.isObject()) {
            throw new Invalid("Each question must be an object with a header, a question and options.");
        }
        String header = text(node, "header");
        if (header.isEmpty()) {
            throw new Invalid("Each question needs a short header.");
        }
        header = cutAtWord(header, MAX_HEADER);

        String question = text(node, "question");
        if (question.isEmpty()) {
            throw new Invalid("Each question needs its full text, ending with a question mark.");
        }
        if (question.length() > MAX_QUESTION) {
            throw new Invalid("Keep each question to 300 characters or fewer.");
        }

        JsonNode optionNodes = node.get("options");
        if (optionNodes == null
                || !optionNodes.isArray()
                || optionNodes.size() < MIN_OPTIONS
                || optionNodes.size() > MAX_OPTIONS) {
            throw new Invalid("Give each question two to four options.");
        }

        List<Option> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int suffixRecommended = -1;
        for (JsonNode optionNode : optionNodes) {
            if (!optionNode.isObject()) {
                throw new Invalid("Give each option a short label and a one-line description.");
            }
            String label = text(optionNode, "label");
            boolean marked = false;
            if (label.toLowerCase(Locale.ROOT).endsWith(RECOMMENDED_SUFFIX)) {
                label = label.substring(0, label.length() - RECOMMENDED_SUFFIX.length())
                        .strip();
                marked = true;
            }
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                throw new Invalid("Keep each option label to 60 characters or fewer.");
            }
            if ("other".equalsIgnoreCase(label)) {
                throw new Invalid("Do not add an Other option; the person can always write their own answer.");
            }
            if (!seen.add(label.toLowerCase(Locale.ROOT))) {
                throw new Invalid("Option labels must differ within a question.");
            }
            String description = text(optionNode, "description");
            if (description.isEmpty()) {
                throw new Invalid("Give each option a one-line description.");
            }
            if (marked && suffixRecommended < 0) {
                suffixRecommended = options.size();
            }
            options.add(new Option(label, cutAtWord(description, MAX_DESCRIPTION), false));
        }

        // The index wins over a suffix, and at most one option is recommended.
        int recommended = suffixRecommended;
        JsonNode index = node.get("recommended");
        if (index != null && !index.isNull()) {
            if (!index.canConvertToInt()
                    || !index.isIntegralNumber()
                    || index.asInt() < 0
                    || index.asInt() >= options.size()) {
                throw new Invalid("The recommended index must point at one of the options, counting from 0.");
            }
            recommended = index.asInt();
        }

        List<Option> ordered = new ArrayList<>(options.size());
        if (recommended >= 0) {
            Option chosen = options.get(recommended);
            ordered.add(new Option(chosen.label(), chosen.description(), true));
        }
        for (int i = 0; i < options.size(); i++) {
            if (i != recommended) {
                ordered.add(options.get(i));
            }
        }

        boolean multiSelect = node.path("multiSelect").asBoolean(false);
        return new Question(id, header, question, multiSelect, List.copyOf(ordered));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    /** Shortens to the last space within {@code max} characters, or hard-cuts when there is none. */
    static String cutAtWord(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        String head = value.substring(0, max);
        if (value.charAt(max) == ' ') {
            return head.strip();
        }
        int space = head.lastIndexOf(' ');
        return (space > 0 ? head.substring(0, space) : head).strip();
    }
}
