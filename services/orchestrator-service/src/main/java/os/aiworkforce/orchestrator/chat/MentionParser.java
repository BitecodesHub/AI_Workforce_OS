// @find: mention parser, @agent mention, address an agent, @research, agent name or key, MentionParser, strip mention from text, chat mention
// @what: Finds @agent mentions in a chat message and resolves them to real agents.
// @flow: Called by CoordinatorService before intent detection.
package os.aiworkforce.orchestrator.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import os.aiworkforce.orchestrator.domain.Agent;

/**
 * Finds {@code @agent} mentions in a chat message.
 *
 * <p>A mention matches an agent's key or its name, ignoring case and ignoring spaces and hyphens,
 * so {@code @CustomerSupport}, {@code @customer-support} and {@code @Customer Support} (the two
 * words, each written out and followed by a space or the end of the message) all address the same
 * agent named "Customer Support". Matching is exact once both sides are normalised: a token that
 * does not resolve to a real agent is left in the text untouched, because it is not a mention at
 * all, just a person typing an "@" for some other reason.
 */
public final class MentionParser {

    private MentionParser() {}

    /** The token a mention starts with: an "@" followed by a run of letters, digits or hyphens. */
    private static final Pattern TOKEN = Pattern.compile("@([\\p{L}\\p{N}][\\p{L}\\p{N}-]*)");

    /** A further word a multi-word name's mention might continue into, joined by a space or hyphen. */
    private static final Pattern NEXT_WORD = Pattern.compile("[ -]([\\p{L}\\p{N}]+)");

    /** More than this many extra words is not a name any agent in this workspace actually has. */
    private static final int MAX_EXTRA_WORDS = 3;

    /** @param agents the agents matched, in the order they were written, without duplicates */
    public record Result(List<Agent> agents, String text) {}

    // @find: parse mentions, find @agent in message
    public static Result parse(String text, List<Agent> agents) {
        if (text == null || text.isBlank() || agents == null || agents.isEmpty()) {
            return new Result(List.of(), text == null ? "" : text);
        }

        Map<String, Agent> byNormalised = new LinkedHashMap<>();
        for (Agent agent : agents) {
            if (agent.getKey() != null && !agent.getKey().isBlank()) {
                byNormalised.putIfAbsent(normalise(agent.getKey()), agent);
            }
            if (agent.getName() != null && !agent.getName().isBlank()) {
                byNormalised.putIfAbsent(normalise(agent.getName()), agent);
            }
        }

        StringBuilder cleaned = new StringBuilder();
        List<Agent> found = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        int consumedUpTo = 0;

        while (matcher.find()) {
            if (matcher.start() < consumedUpTo) {
                // Already swallowed as the tail of an earlier, multi-word mention.
                continue;
            }

            String combined = normalise(matcher.group(1));
            Agent matched = byNormalised.get(combined);
            int matchedEnd = matched != null ? matcher.end() : -1;

            int probeEnd = matcher.end();
            for (int extra = 0; extra < MAX_EXTRA_WORDS; extra++) {
                Matcher nextWord = NEXT_WORD.matcher(text);
                nextWord.region(probeEnd, text.length());
                if (!nextWord.lookingAt()) {
                    break;
                }
                combined = combined + normalise(nextWord.group(1));
                probeEnd = nextWord.end();
                Agent candidate = byNormalised.get(combined);
                if (candidate != null) {
                    matched = candidate;
                    matchedEnd = probeEnd;
                }
            }

            cleaned.append(text, consumedUpTo, matcher.start());
            if (matched != null) {
                if (seen.add(matched.getId())) {
                    found.add(matched);
                }
                consumedUpTo = matchedEnd;
            } else {
                // No agent recognises this token; it is not a mention, so it is left as written.
                cleaned.append(text, matcher.start(), matcher.end());
                consumedUpTo = matcher.end();
            }
        }
        cleaned.append(text.substring(consumedUpTo));

        String withoutMentions =
                cleaned.toString().replaceAll("[ \\t]{2,}", " ").strip();
        return new Result(List.copyOf(found), withoutMentions);
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "");
    }
}
