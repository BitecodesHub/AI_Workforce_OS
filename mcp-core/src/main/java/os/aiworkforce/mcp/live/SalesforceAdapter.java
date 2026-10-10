// @find: salesforce, crm, accounts, opportunities, leads, search accounts, list opportunities, get opportunity, create lead, update opportunity, SOQL, instance url, OAuth, live adapter, real API, sales
// @what: Live Salesforce connector: runs salesforce__ tools (accounts, opportunities, leads) against the connected org's instance URL with an OAuth access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * The Salesforce REST API. The credential is JSON holding the access token and the instance
 * address the token belongs to; the address must be a Salesforce domain or nothing is sent.
 */
public final class SalesforceAdapter extends OAuthAdapter {

    public static final String BASE_URL = null;

    private static final String DATA = "/services/data/v60.0";
    private static final Pattern RECORD_ID = Pattern.compile("[a-zA-Z0-9]{15}|[a-zA-Z0-9]{18}");
    private static final int TERM_LIMIT = 100;

    private final boolean fixedBase;

    public SalesforceAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Salesforce", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("search_accounts", this::searchAccounts);
        on("list_opportunities", this::listOpportunities);
        on("get_opportunity", this::getOpportunity);
        on("create_lead", this::createLead);
        on("update_opportunity", this::updateOpportunity);
    }

    /** Checks the credential once, before any request, so a bad instance address sends nothing. */
    @Override
    protected Mono<String> prepare(String credential) {
        parts(credential);
        return Mono.just(credential);
    }

    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        headers.setBearerAuth(parts(credential).accessToken());
    }

    @Override
    protected String baseFor(String credential) {
        String instance = parts(credential).instanceUrl();
        return fixedBase ? null : instance;
    }

    @Override
    protected Mono<String> whoAmI(String credential) {
        return get(credential, "/services/oauth2/userinfo").flatMap(user -> {
            String name = user.path("name").asText("");
            if (name.isEmpty()) {
                name = user.path("preferred_username").asText("Salesforce user");
            }
            String label = name;
            return get(credential, DATA + "/query?q={q}", "SELECT Name FROM Organization LIMIT 1")
                    .map(org -> org.path("records").path(0).path("Name").asText(""))
                    .onErrorReturn("")
                    .map(org -> org.isEmpty() ? label : label + " (" + org + ")");
        });
    }

    // @find: Salesforce search accounts, tool salesforce__search_accounts, live Salesforce call
    private Mono<ToolResult> searchAccounts(ToolInvocation invocation, JsonNode arguments, String token) {
        String term = required(arguments, "query");
        String soql = "SELECT Id,Name,Industry,BillingCity FROM Account WHERE Name LIKE '%" + likeTerm(term)
                + "%' LIMIT " + limit(arguments, 20, 100);
        return get(token, DATA + "/query?q={q}", soql).map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("records").forEach(account -> {
                ObjectNode item = items.addObject();
                item.put("id", account.path("Id").asText());
                item.put("name", account.path("Name").asText());
                item.put("industry", account.path("Industry").asText(null));
                item.put("city", account.path("BillingCity").asText(null));
            });
            return done(listOf(items), "Found " + items.size() + " account(s) in Salesforce.");
        });
    }

    // @find: Salesforce list opportunities, tool salesforce__list_opportunities, live Salesforce call
    private Mono<ToolResult> listOpportunities(ToolInvocation invocation, JsonNode arguments, String token) {
        String stage = text(arguments, "stage");
        String soql = "SELECT Id,Name,Account.Name,StageName,Amount,CloseDate FROM Opportunity"
                + (stage == null ? "" : " WHERE StageName = '" + literal(stage) + "'")
                + " ORDER BY CloseDate DESC LIMIT " + limit(arguments, 20, 100);
        return get(token, DATA + "/query?q={q}", soql).map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("records").forEach(opportunity -> items.add(opportunity(opportunity)));
            return done(listOf(items), "Read " + items.size() + " opportunity(ies) from Salesforce.");
        });
    }

    // @find: Salesforce get opportunity, tool salesforce__get_opportunity, live Salesforce call
    private Mono<ToolResult> getOpportunity(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = recordId(required(arguments, "id"));
        return get(token, DATA + "/sobjects/Opportunity/{id}", id).map(opportunity -> {
            ObjectNode item = opportunity(opportunity);
            item.put("accountId", opportunity.path("AccountId").asText(null));
            item.put("probability", opportunity.path("Probability").asText(null));
            item.put("description", opportunity.path("Description").asText(null));
            return done(item, "Read the opportunity \"" + opportunity.path("Name").asText() + "\" from Salesforce.");
        });
    }

    // @find: Salesforce create lead, tool salesforce__create_lead, live Salesforce call
    private Mono<ToolResult> createLead(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        String first = text(arguments, "firstName");
        if (first != null) {
            body.put("FirstName", first);
        }
        body.put("LastName", required(arguments, "lastName"));
        body.put("Company", required(arguments, "company"));
        String email = text(arguments, "email");
        if (email != null) {
            body.put("Email", email);
        }
        return send(HttpMethod.POST, token, body, DATA + "/sobjects/Lead").map(created -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", created.path("id").asText());
            return done(item, "Added " + body.path("LastName").asText() + " as a lead in Salesforce.");
        });
    }

    // @find: Salesforce update opportunity, tool salesforce__update_opportunity, live Salesforce call
    private Mono<ToolResult> updateOpportunity(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = recordId(required(arguments, "id"));
        ObjectNode body = json.createObjectNode();
        String stage = text(arguments, "stage");
        if (stage != null) {
            body.put("StageName", stage);
        }
        String amount = text(arguments, "amount");
        if (amount != null) {
            try {
                body.put("Amount", new java.math.BigDecimal(amount));
            } catch (NumberFormatException e) {
                throw new VendorException("The amount must be a number.");
            }
        }
        String closeDate = text(arguments, "closeDate");
        if (closeDate != null) {
            try {
                LocalDate.parse(closeDate);
            } catch (DateTimeParseException e) {
                throw new VendorException("The closeDate must look like 2026-11-15.");
            }
            body.put("CloseDate", closeDate);
        }
        if (body.isEmpty()) {
            throw new VendorException("Give a stage, an amount or a closeDate to change.");
        }
        return send(HttpMethod.PATCH, token, body, DATA + "/sobjects/Opportunity/{id}", id).map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            item.put("updated", true);
            return done(item, "Updated the opportunity in Salesforce.");
        });
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private ObjectNode opportunity(JsonNode opportunity) {
        ObjectNode item = json.createObjectNode();
        item.put("id", opportunity.path("Id").asText());
        item.put("name", opportunity.path("Name").asText());
        item.put("account", opportunity.path("Account").path("Name").asText(null));
        item.put("stage", opportunity.path("StageName").asText(null));
        if (opportunity.hasNonNull("Amount")) {
            item.set("amount", opportunity.path("Amount"));
        } else {
            item.putNull("amount");
        }
        item.put("closeDate", opportunity.path("CloseDate").asText(null));
        return item;
    }

    private static String recordId(String id) {
        if (!RECORD_ID.matcher(id).matches()) {
            throw new VendorException("That is not a Salesforce record id (15 or 18 letters and digits).");
        }
        return id;
    }

    /** Escapes text placed between single quotes in SOQL. */
    static String literal(String value) {
        String flat = value.replaceAll("[\\r\\n\\t\\x00-\\x1f]", " ").strip();
        if (flat.length() > TERM_LIMIT) {
            flat = flat.substring(0, TERM_LIMIT);
        }
        return flat.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    /** As {@link #literal}, with the LIKE wildcards made literal too. */
    static String likeTerm(String value) {
        return literal(value).replace("%", "\\%").replace("_", "\\_");
    }

    private record Parts(String accessToken, String instanceUrl) {}

    private Parts parts(String credential) {
        JsonNode node;
        try {
            node = json.readTree(credential);
        } catch (Exception e) {
            node = null;
        }
        String token = node == null ? "" : node.path("accessToken").asText("");
        String instance = node == null ? "" : node.path("instanceUrl").asText("");
        if (token.isBlank() || instance.isBlank()) {
            throw new VendorException("The Salesforce connection is incomplete. It needs to be connected again.");
        }
        try {
            return new Parts(token, Hosts.salesforceInstance(instance));
        } catch (IllegalArgumentException e) {
            throw new VendorException(e.getMessage());
        }
    }
}
