// @find: hubspot, crm, contacts, deals, list contacts, search contacts, create contact, list deals, private app token, live adapter, real API, sales
// @what: Live HubSpot connector: runs hubspot__ tools (contacts, deals) against the HubSpot CRM API with a private app token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "hubspot"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; each tool makes one call to api.hubapi.com; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * HubSpot's CRM v3 API with a private app access token.
 *
 * <p>Used by the tool gateway when a workspace has stored a HubSpot private app token. list_contacts,
 * search_contacts and list_deals are read tools; create_contact is a write tool, so a person is
 * asked first when the workspace policy says so. There is no destructive or outbound tool.
 *
 * <p>Failures: a missing argument becomes a plain failed result; HTTP errors are described by the
 * base class. Nothing is retried, so a dropped connection on create_contact is reported as
 * indeterminate rather than repeated.
 */
public final class HubSpotAdapter extends LiveServerAdapter {

    /** The production HubSpot API host; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://api.hubapi.com";

    /** The contact properties requested, so answers stay small and predictable. */
    private static final String CONTACT_PROPERTIES = "firstname,lastname,email,company,phone,lifecyclestage";
    /** The deal properties requested. */
    private static final String DEAL_PROPERTIES = "dealname,amount,dealstage,closedate,pipeline";

    /** Registers the four HubSpot tools. The sandbox supplies the tool definitions. */
    public HubSpotAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "HubSpot", json, http, baseUrl);
        on("list_contacts", this::listContacts);
        on("search_contacts", this::searchContacts);
        on("create_contact", this::createContact);
        on("list_deals", this::listDeals);
    }

    /** Sends the private app token as a bearer token. */
    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
    }

    /**
     * Connection check: GET the account details and answer the portal id. A 403 still counts as a
     * working token that may not read account details; a 401 fails the check as a rejected token.
     */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/account-info/v3/details")
                .map(account -> account.hasNonNull("portalId")
                        ? "HubSpot account " + account.path("portalId").asText()
                        : "HubSpot account")
                // 403 means the token is genuine but may not read account details; 401 means it is not.
                .onErrorResume(
                        WebClientResponseException.Forbidden.class, forbidden -> Mono.just("HubSpot account"));
    }

    // @find: HubSpot list contacts, tool hubspot__list_contacts, live HubSpot call
    /** Read tool. GET one page of contacts, at most 100, with the standard property set. */
    private Mono<ToolResult> listContacts(ToolInvocation invocation, JsonNode arguments, String token) {
        return get(
                        token,
                        "/crm/v3/objects/contacts?limit={limit}&properties={properties}",
                        limit(arguments, 20, 100),
                        CONTACT_PROPERTIES)
                .map(answer -> contacts(answer, "Read "));
    }

    // @find: HubSpot search contacts, tool hubspot__search_contacts, live HubSpot call
    /** Read tool. POST a free-text search to the contacts search endpoint; it changes nothing. A query is required. */
    private Mono<ToolResult> searchContacts(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        body.put("query", required(arguments, "query"));
        body.put("limit", limit(arguments, 20, 100));
        ArrayNode properties = body.putArray("properties");
        for (String property : CONTACT_PROPERTIES.split(",")) {
            properties.add(property);
        }
        return send(HttpMethod.POST, token, body, "/crm/v3/objects/contacts/search")
                .map(answer -> contacts(answer, "Found "));
    }

    // @find: HubSpot create contact, tool hubspot__create_contact, live HubSpot call
    /**
     * Write tool. POST a new contact; the email is required and the name, company and phone are
     * optional. Not idempotent. HubSpot refuses a second contact with the same email, which is
     * reported as a plain failure.
     */
    private Mono<ToolResult> createContact(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        ObjectNode properties = body.putObject("properties");
        properties.put("email", required(arguments, "email"));
        String[][] fields = {{"firstName", "firstname"}, {"lastName", "lastname"}, {"company", "company"}, {"phone", "phone"}};
        for (String[] field : fields) {
            String value = text(arguments, field[0]);
            if (value != null) {
                properties.put(field[1], value);
            }
        }
        return send(HttpMethod.POST, token, body, "/crm/v3/objects/contacts")
                .map(contact -> done(contact(contact), "Added " + properties.path("email").asText() + " to HubSpot."));
    }

    // @find: HubSpot list deals, tool hubspot__list_deals, live HubSpot call
    /** Read tool. GET one page of deals, at most 100, with id, name, stage, amount, close date and pipeline. */
    private Mono<ToolResult> listDeals(ToolInvocation invocation, JsonNode arguments, String token) {
        return get(
                        token,
                        "/crm/v3/objects/deals?limit={limit}&properties={properties}",
                        limit(arguments, 20, 100),
                        DEAL_PROPERTIES)
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("results").forEach(deal -> {
                        JsonNode values = deal.path("properties");
                        ObjectNode item = items.addObject();
                        item.put("id", deal.path("id").asText());
                        item.put("name", values.path("dealname").asText());
                        item.put("stage", values.path("dealstage").asText());
                        item.put("amount", values.path("amount").asText(null));
                        item.put("closeDate", values.path("closedate").asText(null));
                        item.put("pipeline", values.path("pipeline").asText());
                    });
                    return done(listOf(items), "Read " + items.size() + " deal(s) from HubSpot.");
                });
    }

    /** Builds the list result for contact answers; the verb ("Read" or "Found") fits the summary to the tool. */
    private ToolResult contacts(JsonNode answer, String verb) {
        ArrayNode items = json.createArrayNode();
        answer.path("results").forEach(contact -> items.add(contact(contact)));
        return done(listOf(items), verb + items.size() + " contact(s) in HubSpot.");
    }

    /** Maps a HubSpot contact to the plain shape the tools return. */
    private ObjectNode contact(JsonNode contact) {
        JsonNode values = contact.path("properties");
        ObjectNode item = json.createObjectNode();
        item.put("id", contact.path("id").asText());
        item.put("firstName", values.path("firstname").asText(null));
        item.put("lastName", values.path("lastname").asText(null));
        item.put("email", values.path("email").asText(null));
        item.put("company", values.path("company").asText(null));
        item.put("phone", values.path("phone").asText(null));
        item.put("lifecycleStage", values.path("lifecyclestage").asText(null));
        return item;
    }
}
