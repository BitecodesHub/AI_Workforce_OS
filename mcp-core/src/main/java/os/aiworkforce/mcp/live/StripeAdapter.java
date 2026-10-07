package os.aiworkforce.mcp.live;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Stripe's API with a restricted or secret key.
 *
 * <p>Stripe takes form-encoded bodies, and amounts in the smallest currency unit; arguments and
 * results here use whole currency units. A refund carries an {@code Idempotency-Key}, so a retry
 * of the same invocation cannot refund twice.
 */
public final class StripeAdapter extends LiveServerAdapter {

    public static final String BASE_URL = "https://api.stripe.com";

    private static final Set<String> REASONS = Set.of("duplicate", "fraudulent", "requested_by_customer");
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga", "pyg", "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf");

    public StripeAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Stripe", json, http, baseUrl);
        on("search_customers", this::searchCustomers);
        on("list_payments", this::listPayments);
        on("get_payment", this::getPayment);
        on("list_invoices", this::listInvoices);
        on("refund_payment", this::refund);
    }

    @Override
    protected void authorize(HttpHeaders headers, String key) {
        headers.setBearerAuth(key);
    }

    @Override
    protected Mono<String> whoAmI(String key) {
        return get(key, "/v1/balance").map(balance ->
                balance.path("livemode").asBoolean(false) ? "Stripe account (live mode)" : "Stripe account (test mode)");
    }

    private Mono<ToolResult> searchCustomers(ToolInvocation invocation, JsonNode arguments, String key) {
        String query = required(arguments, "query");
        String quoted = "\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        // Name search needs at least three characters.
        String search = query.length() >= 3 ? "email:" + quoted + " OR name~" + quoted : "email:" + quoted;
        return get(key, "/v1/customers/search?query={query}&limit={n}", search, limit(arguments, 10, 100))
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("data").forEach(customer -> {
                        ObjectNode item = items.addObject();
                        item.put("id", customer.path("id").asText());
                        item.put("title", customer.path("name").asText(customer.path("email").asText("")));
                        item.put("email", customer.path("email").asText(null));
                        item.put("createdAt", instant(customer.path("created")));
                    });
                    return done(listOf(items), "Found " + items.size() + " customer(s) in Stripe.");
                });
    }

    private Mono<ToolResult> listPayments(ToolInvocation invocation, JsonNode arguments, String key) {
        String customer = text(arguments, "customer");
        String status = text(arguments, "status");
        int limit = limit(arguments, 10, 100);
        // Stripe cannot filter payments by status, so with a status the widest page is read and trimmed.
        int fetch = status == null ? limit : 100;
        Mono<JsonNode> answer = customer == null
                ? get(key, "/v1/payment_intents?limit={n}", fetch)
                : get(key, "/v1/payment_intents?limit={n}&customer={customer}", fetch, customer);
        return answer.map(body -> {
            ArrayNode items = json.createArrayNode();
            for (JsonNode payment : body.path("data")) {
                if (items.size() >= limit) {
                    break;
                }
                if (status == null || status.equalsIgnoreCase(payment.path("status").asText())) {
                    items.add(payment(payment));
                }
            }
            return done(listOf(items), "Read " + items.size() + " payment(s) from Stripe.");
        });
    }

    private Mono<ToolResult> getPayment(ToolInvocation invocation, JsonNode arguments, String key) {
        String id = required(arguments, "id");
        return get(key, "/v1/payment_intents/{id}", id)
                .map(payment -> done(payment(payment), "Read payment " + id + " from Stripe."));
    }

    private Mono<ToolResult> listInvoices(ToolInvocation invocation, JsonNode arguments, String key) {
        String customer = text(arguments, "customer");
        String status = text(arguments, "status");
        StringBuilder uri = new StringBuilder("/v1/invoices?limit={n}");
        java.util.List<Object> variables = new java.util.ArrayList<>();
        variables.add(limit(arguments, 10, 100));
        if (customer != null) {
            uri.append("&customer={customer}");
            variables.add(customer);
        }
        if (status != null) {
            uri.append("&status={status}");
            variables.add(status.toLowerCase());
        }
        return get(key, uri.toString(), variables.toArray()).map(body -> {
            ArrayNode items = json.createArrayNode();
            body.path("data").forEach(invoice -> {
                ObjectNode item = items.addObject();
                String currency = invoice.path("currency").asText("");
                item.put("id", invoice.path("id").asText());
                item.put("title", invoice.path("number").asText(invoice.path("id").asText()));
                item.put("status", invoice.path("status").asText());
                item.put("customer", invoice.path("customer").asText(null));
                item.put("amount", major(invoice.path("total").asLong(), currency));
                item.put("currency", currency);
                item.put("url", invoice.path("hosted_invoice_url").asText(null));
                item.put("createdAt", instant(invoice.path("created")));
            });
            return done(listOf(items), "Read " + items.size() + " invoice(s) from Stripe.");
        });
    }

    private Mono<ToolResult> refund(ToolInvocation invocation, JsonNode arguments, String key) {
        String payment = required(arguments, "id");
        String reason = text(arguments, "reason");
        if (reason != null && !REASONS.contains(reason)) {
            throw new VendorException("The reason must be duplicate, fraudulent or requested_by_customer.");
        }
        BigDecimal amount = amount(arguments);
        // A partial refund needs the currency to turn the amount into minor units.
        Mono<JsonNode> intent = amount == null ? Mono.empty() : get(key, "/v1/payment_intents/{id}", payment);
        return intent.map(found -> found.path("currency").asText("")).defaultIfEmpty("").flatMap(currency -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("payment_intent", payment);
            if (amount != null) {
                form.add("amount", Long.toString(minor(amount, currency)));
            }
            if (reason != null) {
                form.add("reason", reason);
            }
            String idempotencyKey = invocation.idempotencyKey();
            return http.post()
                    .uri("/v1/refunds")
                    .headers(headers -> {
                        authorize(headers, key);
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class);
        }).map(refund -> {
            ObjectNode item = json.createObjectNode();
            String currency = refund.path("currency").asText("");
            item.put("id", refund.path("id").asText());
            item.put("status", refund.path("status").asText());
            item.put("paymentId", refund.path("payment_intent").asText(payment));
            item.put("amount", major(refund.path("amount").asLong(), currency));
            item.put("currency", currency);
            return done(item, "Refunded " + item.path("amount").asText() + " " + currency.toUpperCase()
                    + " on payment " + payment + " in Stripe.");
        });
    }

    private static BigDecimal amount(JsonNode arguments) {
        String raw = text(arguments, "amount");
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() <= 0) {
                throw new VendorException("The refund amount must be more than zero.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new VendorException("The refund amount must be a number.");
        }
    }

    private static long minor(BigDecimal amount, String currency) {
        return ZERO_DECIMAL.contains(currency.toLowerCase())
                ? amount.setScale(0, RoundingMode.HALF_UP).longValueExact()
                : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal major(long minor, String currency) {
        return ZERO_DECIMAL.contains(currency.toLowerCase())
                ? BigDecimal.valueOf(minor)
                : BigDecimal.valueOf(minor, 2);
    }

    private static String instant(JsonNode seconds) {
        return seconds.isNumber() ? Instant.ofEpochSecond(seconds.asLong()).toString() : "";
    }

    private ObjectNode payment(JsonNode payment) {
        ObjectNode item = json.createObjectNode();
        String currency = payment.path("currency").asText("");
        item.put("id", payment.path("id").asText());
        item.put("title", payment.path("description").asText(payment.path("id").asText()));
        item.put("status", payment.path("status").asText());
        item.put("amount", major(payment.path("amount").asLong(), currency));
        item.put("currency", currency);
        item.put("customer", payment.path("customer").asText(null));
        item.put("createdAt", instant(payment.path("created")));
        return item;
    }
}
