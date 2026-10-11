// @find: microsoft teams, teams, channels, chat, messages, list channels, get messages, post message, Graph API, OAuth, live adapter, real API, communication
// @what: Live Microsoft Teams connector: runs teams__ tools (list channels, read and post messages) against Microsoft Graph with an OAuth access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "teams"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke, and post_message passes the approval gate first; OAuthAdapter supplies the bearer token and the one renew-and-retry after a 401; calls go to graph.microsoft.com, one call per tool plus one per joined team to find a channel; base class OAuthAdapter
package os.aiworkforce.mcp.live;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Microsoft Teams channels through Microsoft Graph with an OAuth access token.
 *
 * <p>Used by the tool gateway when a workspace has connected a Microsoft account. list_channels and
 * get_messages are read tools; post_message is outbound and always asks a person first, because it
 * publishes text in a channel. A post is not idempotent, so a dropped connection is reported as
 * indeterminate and the post is never repeated.
 *
 * <p>Failures: a missing argument, an unknown or ambiguous team or channel becomes a plain failed
 * result; a 401 triggers one token renewal in the base class.
 */
public final class TeamsAdapter extends OAuthAdapter {

    /** The production Microsoft Graph address; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://graph.microsoft.com/v1.0";

    /** The most joined teams searched, to bound the number of calls made when a channel is looked up by name. */
    private static final int MAX_TEAMS = 25;
    /** How many teams have their channels fetched at the same time. */
    private static final int TEAM_CONCURRENCY = 3;

    /** A joined team: its id and display name. */
    private record Team(String id, String name) {}

    /** A channel with the team it belongs to; the team name and description may be empty when the channel was named directly. */
    private record Channel(String teamId, String team, String id, String name, String description) {}

    /** Registers the three Teams tools. The sandbox supplies the tool definitions. */
    public TeamsAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Microsoft Teams", json, http, baseUrl);
        on("list_channels", this::listChannels);
        on("get_messages", this::getMessages);
        on("post_message", this::postMessage);
    }

    /** Connection check: GET the signed-in user; answers the mail address, else the sign-in name, else the display name. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/me").map(me -> {
            String label = me.path("mail").asText("");
            if (label.isEmpty()) {
                label = me.path("userPrincipalName").asText("");
            }
            if (label.isEmpty()) {
                label = me.path("displayName").asText("");
            }
            return label.isEmpty() ? "Microsoft Teams account" : label;
        });
    }

    // @find: Microsoft Teams list channels, tool teams__list_channels, live Microsoft Teams call
    /**
     * Read tool. Lists the channels of the joined teams (at most 25 teams), or of one team when a team
     * name or id is given. Costs one call for the teams plus one per team. Fails when the team name is
     * unknown or matches more than one team.
     */
    private Mono<ToolResult> listChannels(ToolInvocation invocation, JsonNode arguments, String token) {
        String requested = text(arguments, "team");
        return teams(token).flatMap(teams -> {
            List<Team> chosen = teams;
            if (requested != null) {
                chosen = matchTeams(teams, requested);
                if (chosen.isEmpty()) {
                    throw new VendorException("No team called \"" + requested + "\" was found among the joined teams.");
                }
                if (chosen.size() > 1) {
                    throw new VendorException("More than one team is called \"" + requested + "\". Use the team id.");
                }
            }
            return channelsOf(token, chosen).map(channels -> {
                ArrayNode items = json.createArrayNode();
                channels.forEach(channel -> {
                    ObjectNode item = items.addObject();
                    item.put("id", channel.id());
                    item.put("team", channel.team());
                    item.put("teamId", channel.teamId());
                    item.put("name", channel.name());
                    item.put("description", channel.description());
                });
                return done(listOf(items), "Found " + items.size() + " channel(s) in Microsoft Teams.");
            });
        });
    }

    // @find: Microsoft Teams get messages, tool teams__get_messages, live Microsoft Teams call
    /**
     * Read tool. Resolves the channel, then GET its latest messages, at most 50. HTML bodies are reduced to
     * text. Needs the channel read permission granted to the connection.
     */
    private Mono<ToolResult> getMessages(ToolInvocation invocation, JsonNode arguments, String token) {
        String reference = required(arguments, "channel");
        int top = Math.min(limit(arguments, 20, 50), 50);
        return resolve(token, reference).flatMap(channel -> get(
                        token,
                        "/teams/{team}/channels/{channel}/messages?$top={top}",
                        channel.teamId(),
                        channel.id(),
                        top)
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("value").forEach(message -> {
                        JsonNode from = message.path("from");
                        String sender = from.path("user").path("displayName").asText("");
                        if (sender.isEmpty()) {
                            sender = from.path("application").path("displayName").asText("");
                        }
                        JsonNode body = message.path("body");
                        String content = body.path("content").asText("");
                        ObjectNode item = items.addObject();
                        item.put("id", message.path("id").asText());
                        item.put("from", sender);
                        item.put("text", "html".equalsIgnoreCase(body.path("contentType").asText()) ? plain(content) : content);
                        item.put("createdAt", message.path("createdDateTime").asText(""));
                    });
                    return done(listOf(items), "Read " + items.size() + " message(s) from Teams.");
                }));
    }

    // @find: Microsoft Teams post message, tool teams__post_message, live Microsoft Teams call
    /**
     * Outbound tool; a person always approves it first. Resolves the channel, then POST the text as a new
     * message from the signed-in account. Fails before any call when the channel or text is missing.
     */
    private Mono<ToolResult> postMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String reference = required(arguments, "channel");
        String content = required(arguments, "text");
        return resolve(token, reference).flatMap(channel -> {
            ObjectNode body = json.createObjectNode();
            body.putObject("body").put("content", content);
            return send(
                            HttpMethod.POST,
                            token,
                            body,
                            "/teams/{team}/channels/{channel}/messages",
                            channel.teamId(),
                            channel.id())
                    .map(posted -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("id", posted.path("id").asText());
                        item.put("createdAt", posted.path("createdDateTime").asText(null));
                        item.put("channel", channel.name());
                        return done(item, "Posted a message to the Teams channel.");
                    });
        });
    }

    // ---- Resolving teams and channels ---------------------------------------------------------

    /** GET the joined teams, keeping at most the first 25. */
    private Mono<List<Team>> teams(String token) {
        return get(token, "/me/joinedTeams").map(answer -> {
            List<Team> teams = new ArrayList<>();
            answer.path("value").forEach(team -> {
                if (teams.size() < MAX_TEAMS) {
                    teams.add(new Team(team.path("id").asText(), team.path("displayName").asText("")));
                }
            });
            return teams;
        });
    }

    /** Teams whose id matches the request; when none does, teams whose name matches, ignoring case. */
    private static List<Team> matchTeams(List<Team> teams, String requested) {
        List<Team> byId = teams.stream().filter(team -> team.id().equalsIgnoreCase(requested)).toList();
        return byId.isEmpty()
                ? teams.stream().filter(team -> team.name().equalsIgnoreCase(requested)).toList()
                : byId;
    }

    /** GET the channels of each team, a few teams at a time, and return them in team order. */
    private Mono<List<Channel>> channelsOf(String token, List<Team> teams) {
        return Flux.fromIterable(teams)
                .flatMapSequential(
                        team -> get(token, "/teams/{team}/channels", team.id()).map(answer -> {
                            List<Channel> channels = new ArrayList<>();
                            answer.path("value").forEach(channel -> channels.add(new Channel(
                                    team.id(),
                                    team.name(),
                                    channel.path("id").asText(),
                                    channel.path("displayName").asText(""),
                                    channel.path("description").asText(""))));
                            return channels;
                        }),
                        TEAM_CONCURRENCY)
                .collectList()
                .map(lists -> lists.stream().flatMap(List::stream).toList());
    }

    /**
     * "teamId/channelId", or a channel id or name found among the joined teams.
     *
     * Turns a channel reference into a channel. The form teamId/channelId needs no lookup and makes no
     * call; an id or a name is searched among all joined teams, which costs one call per team. Fails
     * with a plain message when nothing or more than one channel matches.
     */
    private Mono<Channel> resolve(String token, String reference) {
        if (reference.contains("/")) {
            String[] parts = reference.split("/", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return Mono.error(new VendorException("The channel must be a name, an id, or <teamId>/<channelId>."));
            }
            return Mono.just(new Channel(parts[0].strip(), "", parts[1].strip(), parts[1].strip(), ""));
        }
        return teams(token).flatMap(teams -> channelsOf(token, teams)).map(channels -> {
            List<Channel> matches = channels.stream().filter(c -> c.id().equals(reference)).toList();
            if (matches.isEmpty()) {
                matches = channels.stream().filter(c -> c.name().equalsIgnoreCase(reference)).toList();
            }
            if (matches.isEmpty()) {
                throw new VendorException("No channel called \"" + reference + "\" was found in the joined teams.");
            }
            if (matches.size() > 1) {
                throw new VendorException("More than one channel is called \"" + reference
                        + "\". Use <teamId>/<channelId> to say which.");
            }
            return matches.get(0);
        });
    }
}
