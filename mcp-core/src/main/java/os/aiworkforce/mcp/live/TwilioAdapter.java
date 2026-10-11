// @find: twilio, phone numbers, list numbers, search available numbers, buy number, release number, connect number to ElevenLabs, import twilio number, account sid, auth token, monthly cost, live adapter, TwilioAdapter
// @what: Live Twilio connector: lists, finds, buys and releases the account's phone numbers, and connects a number to ElevenLabs so agents can place calls from it.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; calls api.twilio.com with the Account SID and Auth token, and ElevenLabsConvai with the workspace's ElevenLabs key
package os.aiworkforce.mcp.live;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import os.aiworkforce.mcp.builtin.ElevenLabsConvai;
import os.aiworkforce.mcp.builtin.PhoneNumbers;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.spi.WorkspaceSecrets;

/**
 * Twilio with an Account SID and Auth token.
 *
 * <p>The stored credential is JSON: {@code {"accountSid":"AC...","authToken":"..."}}; Twilio
 * authenticates with the SID as the user name and the token as the password. Buying a number costs
 * money every month and releasing one gives it up for good, so {@code buy_number} is OUTBOUND and
 * {@code release_number} DESTRUCTIVE: both always wait for a person. Connecting a number to
 * ElevenLabs is a write, but it hands the Auth token to ElevenLabs, so it asks first as well
 * ({@link #approvalReason}).
 */
public final class TwilioAdapter extends LiveServerAdapter {

    public static final String BASE_URL = "https://api.twilio.com";
    static final String PRICING_URL = "https://pricing.twilio.com";

    private static final Pattern ACCOUNT_SID = Pattern.compile("^AC[0-9a-fA-F]{32}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    static final String SID_FORMAT = "The Account SID starts with AC and is 34 characters long. Copy it from the"
            + " Twilio Console, under Account info.";

    private final String pricingBase;
    private ElevenLabsConvai elevenLabs;
    private Supplier<WorkspaceSecrets> secrets = () -> null;

    public TwilioAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Twilio", json, http, baseUrl);
        // A stand-in provider in tests answers pricing at its own address.
        this.pricingBase = baseUrl == null || BASE_URL.equals(baseUrl) ? PRICING_URL : baseUrl;
        this.elevenLabs = new ElevenLabsConvai(json, http, null);
        on("list_numbers", this::listNumbers);
        on("search_available_numbers", this::searchAvailable);
        on("buy_number", this::buyNumber);
        on("release_number", this::releaseNumber);
        on("connect_number_to_elevenlabs", this::connectToElevenLabs);
    }

    /** Where ElevenLabs is and how the workspace's key is found, for the tools that reach it. */
    public TwilioAdapter useElevenLabs(WebClient.Builder http, String baseUrl, Supplier<WorkspaceSecrets> secrets) {
        this.elevenLabs = new ElevenLabsConvai(json, http, baseUrl);
        this.secrets = secrets == null ? () -> null : secrets;
        return this;
    }

    record Account(String sid, String token) {}

    // @find: parse twilio credential, account sid format check
    Account account(String credential) {
        JsonNode node;
        try {
            node = json.readTree(credential);
        } catch (Exception e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw new VendorException("The Twilio credential needs the Account SID and the Auth token.");
        }
        String sid = text(node, "accountSid");
        String token = text(node, "authToken");
        if (sid == null || token == null) {
            throw new VendorException("The Twilio credential needs the Account SID and the Auth token.");
        }
        if (!ACCOUNT_SID.matcher(sid).matches()) {
            throw new VendorException(SID_FORMAT);
        }
        return new Account(sid, token);
    }

    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        Account account = account(credential);
        headers.setBasicAuth(account.sid(), account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    @Override
    protected Mono<String> whoAmI(String credential) {
        Account account = account(credential);
        return get(credential, "/2010-04-01/Accounts/{sid}.json", account.sid())
                .map(answer -> answer.path("friendly_name").asText("Twilio account " + account.sid()));
    }

    // @find: connect number to ElevenLabs asks first
    @Override
    public Optional<String> approvalReason(ToolInvocation invocation) {
        if (!"connect_number_to_elevenlabs".equals(invocation.tool())) {
            return Optional.empty();
        }
        String number = "";
        try {
            number = json.readTree(invocation.argumentsJson()).path("phone_number").asText("");
        } catch (Exception ignored) {
            // Validated by the gateway already; the number is only for the sentence.
        }
        return Optional.of("Connect the Twilio number " + number + " to ElevenLabs, which shares the Twilio Auth token"
                + " with ElevenLabs, using twilio.connect_number_to_elevenlabs");
    }

    // ---- Numbers on the account ---------------------------------------------------------------

    // @find: Twilio list numbers, tool twilio__list_numbers, live Twilio call
    private Mono<ToolResult> listNumbers(ToolInvocation invocation, JsonNode arguments, String credential) {
        Account account = account(credential);
        int limit = limit(arguments, 50, 200);
        return get(credential, "/2010-04-01/Accounts/{sid}/IncomingPhoneNumbers.json?PageSize={n}", account.sid(), limit)
                .flatMap(answer -> importedNumbers(invocation.orgId()).map(imported -> {
                    ArrayNode items = json.createArrayNode();
                    for (JsonNode number : answer.path("incoming_phone_numbers")) {
                        String phone = number.path("phone_number").asText("");
                        ObjectNode item = items.addObject()
                                .put("sid", number.path("sid").asText(""))
                                .put("phone_number", phone)
                                .put("friendly_name", number.path("friendly_name").asText(""))
                                .put("voice", number.path("capabilities").path("voice").asBoolean(false));
                        if (imported.isEmpty()) {
                            item.put("in_elevenlabs", "unknown");
                        } else {
                            Optional<JsonNode> match = imported.get().stream()
                                    .filter(n -> phone.equals(n.path("phone_number").asText()))
                                    .findFirst();
                            item.put("in_elevenlabs", match.isPresent() ? "yes" : "no");
                            match.ifPresent(n -> item.put("elevenlabs_agent",
                                    n.path("assigned_agent").path("agent_name").asText("")));
                        }
                    }
                    return done(listOf(items), "Read " + items.size() + " phone number(s) on the Twilio account.");
                }));
    }

    /* The numbers already in ElevenLabs, or empty when that cannot be known (no key, or ElevenLabs down). */
    private Mono<Optional<List<JsonNode>>> importedNumbers(String orgId) {
        // The key store is asked with a blocking call, so never on the HTTP client's own threads.
        return Mono.fromCallable(() -> elevenLabsKey(orgId, false))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(key -> key.isEmpty()
                        ? Mono.just(Optional.<List<JsonNode>>empty())
                        : elevenLabs.phoneNumbers(key.get()).map(Optional::of))
                .onErrorResume(WebClientResponseException.class, refused -> Mono.just(Optional.empty()))
                .onErrorResume(WebClientRequestException.class, down -> Mono.just(Optional.empty()));
    }

    // @find: Twilio search available numbers, tool twilio__search_available_numbers, live Twilio call
    private Mono<ToolResult> searchAvailable(ToolInvocation invocation, JsonNode arguments, String credential) {
        Account account = account(credential);
        String country = required(arguments, "country_code").toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) {
            throw new VendorException("The country_code is the two-letter country code, for example AU or US.");
        }
        String areaCode = text(arguments, "area_code");
        String contains = text(arguments, "contains");
        int limit = limit(arguments, 10, 30);
        StringBuilder uri = new StringBuilder(
                "/2010-04-01/Accounts/{sid}/AvailablePhoneNumbers/{country}/Local.json?VoiceEnabled=true&PageSize={n}");
        List<Object> variables = new java.util.ArrayList<>(List.of(account.sid(), country, limit));
        if (areaCode != null) {
            if (!areaCode.matches("[0-9]{1,5}")) {
                throw new VendorException("The area_code is digits only, for example 2 or 415.");
            }
            uri.append("&AreaCode={area}");
            variables.add(areaCode);
        }
        if (contains != null) {
            if (!contains.matches("[0-9A-Za-z*]{1,16}")) {
                throw new VendorException("The contains pattern is digits, letters or *, for example 555 or *CARE*.");
            }
            uri.append("&Contains={contains}");
            variables.add(contains);
        }
        return get(credential, uri.toString(), variables.toArray()).map(answer -> {
            ArrayNode items = json.createArrayNode();
            for (JsonNode number : answer.path("available_phone_numbers")) {
                items.addObject()
                        .put("phone_number", number.path("phone_number").asText(""))
                        .put("friendly_name", number.path("friendly_name").asText(""))
                        .put("locality", number.path("locality").asText(""))
                        .put("region", number.path("region").asText(""))
                        .put("country", number.path("iso_country").asText(country))
                        .put("voice", number.path("capabilities").path("voice").asBoolean(true));
            }
            return done(listOf(items), "Found " + items.size() + " number(s) available to buy in " + country + ".");
        });
    }

    // @find: Twilio buy number, tool twilio__buy_number, purchase phone number, monthly cost
    private Mono<ToolResult> buyNumber(ToolInvocation invocation, JsonNode arguments, String credential) {
        Account account = account(credential);
        String number = checkedNumber(arguments);
        String country = text(arguments, "country_code");
        Mono<Optional<String>> price = country == null || !COUNTRY.matcher(country.toUpperCase(Locale.ROOT)).matches()
                ? Mono.just(Optional.empty())
                : monthlyPrice(credential, country.toUpperCase(Locale.ROOT));
        return price.flatMap(cost -> form(credential, "/2010-04-01/Accounts/{sid}/IncomingPhoneNumbers.json",
                        formOf("PhoneNumber", number), account.sid())
                .map(bought -> {
                    ObjectNode item = json.createObjectNode()
                            .put("sid", bought.path("sid").asText(""))
                            .put("phone_number", bought.path("phone_number").asText(number))
                            .put("friendly_name", bought.path("friendly_name").asText(""));
                    cost.ifPresent(text -> item.put("monthly_cost", text));
                    return done(item, "Bought " + item.path("phone_number").asText() + " on the Twilio account"
                            + cost.map(text -> ", about " + text + " a month.").orElse("."));
                }));
    }

    /* The local-number monthly price for a country, as "6.50 USD"; empty when Twilio does not say. */
    private Mono<Optional<String>> monthlyPrice(String credential, String country) {
        return http.get()
                .uri(pricingBase + "/v1/PhoneNumbers/Countries/{country}", country)
                .headers(headers -> authorize(headers, credential))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(answer -> {
                    String unit = answer.path("price_unit").asText("USD");
                    for (JsonNode price : answer.path("phone_number_prices")) {
                        if ("local".equals(price.path("number_type").asText())) {
                            String amount = price.path("current_price").asText(price.path("base_price").asText(""));
                            return amount.isBlank() ? Optional.<String>empty() : Optional.of(amount + " " + unit);
                        }
                    }
                    return Optional.<String>empty();
                })
                .onErrorResume(WebClientResponseException.class, refused -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty());
    }

    // @find: Twilio release number, tool twilio__release_number, give up phone number
    private Mono<ToolResult> releaseNumber(ToolInvocation invocation, JsonNode arguments, String credential) {
        Account account = account(credential);
        String number = checkedNumber(arguments);
        return owned(credential, account, number).flatMap(found -> call(HttpMethod.DELETE, credential,
                        "/2010-04-01/Accounts/{sid}/IncomingPhoneNumbers/{pn}.json", account.sid(),
                        found.path("sid").asText())
                .map(gone -> done(json.createObjectNode().put("phone_number", number).put("released", true),
                        "Released " + number + " from the Twilio account. It can no longer take or make calls.")));
    }

    // @find: Twilio connect number to ElevenLabs, tool twilio__connect_number_to_elevenlabs, import number
    private Mono<ToolResult> connectToElevenLabs(ToolInvocation invocation, JsonNode arguments, String credential) {
        Account account = account(credential);
        String number = checkedNumber(arguments);
        String label = Optional.ofNullable(text(arguments, "label")).orElse("Twilio " + number);
        String agent = text(arguments, "agent");
        return owned(credential, account, number).flatMap(found -> {
            if (!found.path("capabilities").path("voice").asBoolean(true)) {
                throw new VendorException(number + " cannot make voice calls, so it was not connected to ElevenLabs.");
            }
            return Mono.fromCallable(() -> elevenLabsKey(invocation.orgId(), true).orElseThrow())
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(key -> connect(key, account, number, label, agent));
        });
    }

    private Mono<ToolResult> connect(String key, Account account, String number, String label, String agent) {
            return elevenLabs.phoneNumbers(key)
                    .flatMap(existing -> {
                        Optional<JsonNode> already = existing.stream()
                                .filter(n -> number.equals(n.path("phone_number").asText()))
                                .findFirst();
                        return already.isPresent()
                                ? Mono.just(new String[] {already.get().path("phone_number_id").asText(), "existing"})
                                : elevenLabs.importTwilioNumber(key, number, label, account.sid(), account.token())
                                        .map(id -> new String[] {id, "new"});
                    })
                    .flatMap(imported -> {
                        if (imported[0].isBlank()) {
                            throw new VendorException("ElevenLabs did not say which id it gave the number.");
                        }
                        ObjectNode item = json.createObjectNode()
                                .put("phone_number", number)
                                .put("phone_number_id", imported[0]);
                        String first = "existing".equals(imported[1])
                                ? number + " was already connected to ElevenLabs."
                                : "Connected " + number + " to ElevenLabs.";
                        if (agent == null) {
                            return Mono.just(done(item, first));
                        }
                        return elevenLabs.findAgent(key, agent).flatMap(match -> {
                            if (match.isEmpty()) {
                                return Mono.just(done(item, first + " No voice agent is called \"" + agent
                                        + "\", so none was assigned."));
                            }
                            item.put("agent_id", match.get().id()).put("agent", match.get().name());
                            return elevenLabs.assignAgent(key, imported[0], match.get().id())
                                    .map(assigned -> done(item, first + " Assigned the voice agent \""
                                            + match.get().name() + "\" to it."));
                        });
                    })
                    .onErrorMap(WebClientResponseException.class, refused -> new VendorException(
                            refused.getStatusCode().value() == 401 || refused.getStatusCode().value() == 403
                                    ? "ElevenLabs did not accept the stored ElevenLabs key. An administrator can store"
                                            + " a new one on the Connectors page."
                                    : "ElevenLabs refused to connect the number (status "
                                            + refused.getStatusCode().value() + ")."));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /* The number on this account, or a plain refusal when it is not one of the account's numbers. */
    private Mono<JsonNode> owned(String credential, Account account, String number) {
        return get(credential, "/2010-04-01/Accounts/{sid}/IncomingPhoneNumbers.json?PhoneNumber={number}",
                        account.sid(), number)
                .map(answer -> {
                    for (JsonNode found : answer.path("incoming_phone_numbers")) {
                        if (number.equals(found.path("phone_number").asText())) {
                            return found;
                        }
                    }
                    throw new VendorException(number + " is not a number on this Twilio account. Use"
                            + " twilio.list_numbers to see the ones that are.");
                });
    }

    private static String checkedNumber(JsonNode arguments) {
        String number = PhoneNumbers.normalise(required(arguments, "phone_number"));
        String problem = PhoneNumbers.problem(number);
        if (problem != null) {
            throw new VendorException(problem);
        }
        return number;
    }

    private Optional<String> elevenLabsKey(String orgId, boolean required) {
        WorkspaceSecrets source = secrets.get();
        Optional<String> key;
        try {
            key = source == null ? Optional.empty() : source.elevenLabsKey(orgId).filter(value -> !value.isBlank());
        } catch (RuntimeException e) {
            if (required) {
                throw new VendorException("Could not reach the key store, so nothing was connected. Try again shortly.");
            }
            return Optional.empty();
        }
        if (key.isEmpty() && required) {
            throw new VendorException("No ElevenLabs key is stored for this workspace, so the number was not connected."
                    + " An administrator can add it on the Connectors page, under ElevenLabs voice.");
        }
        return key.map(String::strip);
    }

    private static MultiValueMap<String, String> formOf(String name, String value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, value);
        return form;
    }

    /** A form-encoded POST, which is what Twilio's REST API takes. */
    private Mono<JsonNode> form(String credential, String uri, MultiValueMap<String, String> body, Object... variables) {
        return http.post()
                .uri(addressOf(credential, uri), variables)
                .headers(headers -> authorize(headers, credential))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(body))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    private String addressOf(String credential, String uri) {
        String base = baseFor(credential);
        return base != null && uri.startsWith("/") ? base + uri : uri;
    }
}
