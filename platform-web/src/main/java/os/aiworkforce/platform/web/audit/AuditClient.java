// @find: audit client, record audit event, write audit log entry, audit outbox, who did what, action recorded, resource type, outcome, on behalf of
// @what: Lets a service record an audit event into its own outbox in the same transaction as the change.
// @flow: Writes to AuditOutbox; AuditOutboxRelay delivers to analytics-service
package os.aiworkforce.platform.web.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * How a service says something happened that the audit log must remember.
 *
 * <p>{@link #record} writes the event to this service's own {@code audit_outbox} table, inside the
 * transaction of the change it describes when there is one, so the two commit together or not at
 * all. A change that was rolled back leaves no entry; a change that committed cannot lose its entry
 * to an outage of analytics-service, because the entry is already stored locally and the relay will
 * deliver it when it can. Once the transaction commits, the relay is nudged, so the entry normally
 * reaches the audit log within moments.
 *
 * <p>Every event gets its id here, before the first delivery attempt. That id travels with every
 * retry, which is what lets analytics-service recognise a repeat and append the event once.
 *
 * <p>Record <em>who</em> and <em>what</em>, never a secret: a stored credential is recorded by its
 * reference, a password change by the fact of it. As a net under that rule, any detail key that
 * names a password, secret, token or key is replaced before the event is stored.
 */
public class AuditClient {

    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);

    /** The only outcome words an event may carry; anything else is rejected before it is stored. */
    private static final Set<String> OUTCOMES = Set.of("succeeded", "failed", "denied", "locked");

    /**
     * The longest each field is kept, matching what analytics-service accepts. An event that exceeded
     * a limit there would be refused for ever and sit in the outbox as a poison row.
     *
     * Longest actor id kept.
     */
    private static final int ACTOR_ID_MAX = 200;
    /** Longest actor kind kept. */
    private static final int ACTOR_KIND_MAX = 40;
    /** Longest action name kept. */
    private static final int ACTION_MAX = 120;
    /** Longest resource type kept. */
    private static final int RESOURCE_TYPE_MAX = 80;
    /** Longest resource id kept. */
    private static final int RESOURCE_ID_MAX = 200;
    /** Longest request id kept. */
    private static final int REQUEST_ID_MAX = 128;

    /** Beyond this the detail is replaced by a note that it was too large, rather than stored. */
    private static final int DETAIL_MAX_BYTES = 32 * 1024;

    /** Fragments of a detail key that mark its value as a secret to be redacted; see {@link #looksSecret}. */
    private static final Set<String> SECRET_WORDS =
            Set.of("password", "secret", "token", "apikey", "api_key", "authorization", "privatekey", "private_key");

    private final AuditOutbox outbox;
    private final AuditOutboxRelay relay;
    private final ObjectMapper json;
    private final AuditProperties properties;

    /**
     * Creates the client.
     *
     * @param outbox where events are written
     * @param relay nudged once an event is stored
     * @param json writes the detail as JSON text
     * @param properties supplies the enabled switch
     */
    public AuditClient(AuditOutbox outbox, AuditOutboxRelay relay, ObjectMapper json, AuditProperties properties) {
        this.outbox = outbox;
        this.relay = relay;
        this.json = json;
        this.properties = properties;
    }

    /**
     * Records an event for the person or service acting in this request, in their workspace.
     *
     * @param outcome {@code succeeded}, {@code failed}, {@code denied} or {@code locked}
     * @return the event's id
     *
     * Takes the actor from {@code RequestContext}, falling back to the platform's own system actor
     * when the work has no request (a scheduled job, for example), and the workspace from that actor.
     * Delegates to the overload that takes an explicit workspace and actor.
     */
    // @find: record audit event, log who did what
    public UUID record(String action, String resourceType, String resourceId, String outcome, Map<String, Object> detail) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        return record(workspaceOf(actor), actor, action, resourceType, resourceId, outcome, detail);
    }

    /**
     * Records an event in a named workspace, or the platform's own log when {@code orgId} is null.
     *
     * Unpacks the actor's id, kind and on-behalf-of person and delegates to the fully explicit
     * overload. Used by callers that already hold an {@link Actor}.
     *
     * @return the event's id, or {@code null} when auditing is switched off
     */
    public UUID record(
            UUID orgId,
            Actor actor,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail) {
        return record(
                orgId,
                actor.id(),
                actor.kind().name(),
                actor.onBehalfOf(),
                action,
                resourceType,
                resourceId,
                outcome,
                detail);
    }

    /**
     * Records an event with the actor spelled out, for the cases where there is no signed-in actor
     * to read it from: a failed sign-in, a link redeemed by somebody not yet signed in.
     *
     * Validates the outcome, then, when auditing is enabled, creates the event id, truncates each
     * field to the limit analytics-service accepts, redacts secret-looking detail keys and adds the row
     * to the outbox. The write joins the caller's transaction when one is active. The event's time is
     * taken here, not at delivery.
     *
     * <p>Side effects: one outbox insert; the relay is woken after the transaction commits.
     *
     * @return the event's id, or {@code null} when {@code aiwos.audit.enabled} is false
     * @throws IllegalArgumentException when the outcome is not one of the permitted words
     */
    public UUID record(
            UUID orgId,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail) {
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("An audit outcome must be one of " + OUTCOMES + ", not " + outcome);
        }
        if (!properties.enabled()) {
            return null;
        }
        UUID id = UUID.randomUUID();
        outbox.add(new AuditOutbox.Event(
                id,
                orgId,
                cut(actorId, ACTOR_ID_MAX),
                cut(actorKind, ACTOR_KIND_MAX),
                blankToNull(cut(onBehalfOf, ACTOR_ID_MAX)),
                cut(action, ACTION_MAX),
                cut(resourceType, RESOURCE_TYPE_MAX),
                blankToNull(cut(resourceId, RESOURCE_ID_MAX)),
                outcome,
                detailJson(detail),
                cut(RequestContext.requestId(), REQUEST_ID_MAX),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                0));
        nudgeAfterCommit();
        return id;
    }

    /**
     * Sends soon: once the surrounding transaction commits, or at once when there is none.
     *
     * <p>Registers an after-commit callback when a transaction is active, so a rolled-back change never
     * triggers a send; otherwise wakes the relay directly. Called by {@code record} after the outbox write.
     */
    private void nudgeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.wake();
                }
            });
        } else {
            relay.wake();
        }
    }

    /**
     * Writes the redacted detail as JSON text.
     *
     * <p>A missing detail becomes an empty object. Detail larger than the limit is replaced by a note
     * that it was truncated, and detail that cannot be serialised is replaced by a marker object.
     * Neither case throws, because the event must still be recorded.
     */
    private String detailJson(Map<String, Object> detail) {
        try {
            String text = json.writeValueAsString(scrub(detail == null ? Map.of() : detail));
            if (text.length() > DETAIL_MAX_BYTES) {
                return "{\"truncated\":true,\"originalLength\":" + text.length() + "}";
            }
            return text;
        } catch (JsonProcessingException | RuntimeException e) {
            // The event still happened. It is recorded without its detail rather than not at all.
            log.warn("The detail of an audit event could not be written as JSON: {}", e.toString());
            return "{\"detailUnavailable\":true}";
        }
    }

    /** How deep detail may nest before the rest is replaced; real detail is flat or one level in. */
    private static final int MAX_DEPTH = 8;

    /**
     * A copy of the detail with any value under a secret-sounding key replaced.
     *
     * <p>Package-private so tests can call it. Nested maps and collections are cleaned recursively; the
     * input is not modified.
     */
    static Map<String, Object> scrub(Map<String, Object> detail) {
        return scrubMap(detail, 0);
    }

    /**
     * Copies one map, replacing the value of every secret-looking key with {@code [redacted]} and
     * cleaning the other values recursively. Keys are converted to text. Called by {@code scrub} and
     * {@code scrubValue}.
     */
    private static Map<String, Object> scrubMap(Map<?, ?> detail, int depth) {
        Map<String, Object> clean = new LinkedHashMap<>();
        detail.forEach((key, value) -> {
            String name = String.valueOf(key);
            clean.put(name, looksSecret(name) ? "[redacted]" : scrubValue(value, depth + 1));
        });
        return clean;
    }

    /**
     * Cleans one value. Maps and collections nested deeper than {@code MAX_DEPTH} are replaced by a
     * short note, nested maps and collections are copied with their contents cleaned, and any other
     * value is returned unchanged.
     */
    private static Object scrubValue(Object value, int depth) {
        if (depth > MAX_DEPTH && (value instanceof Map<?, ?> || value instanceof Collection<?>)) {
            return "[nested too deeply]";
        }
        if (value instanceof Map<?, ?> nested) {
            return scrubMap(nested, depth);
        }
        if (value instanceof Collection<?> list) {
            List<Object> clean = new ArrayList<>(list.size());
            list.forEach(element -> clean.add(scrubValue(element, depth + 1)));
            return clean;
        }
        return value;
    }

    /**
     * Whether a detail key names a secret.
     *
     * <p>The key is compared in lower case with hyphens and underscores removed. It matches when it
     * contains one of the secret words and does not end in {@code ref}, {@code kind} or {@code id},
     * because those name a reference to a secret, or its type, rather than the secret itself.
     */
    private static boolean looksSecret(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        return SECRET_WORDS.stream().map(word -> word.replace("_", "")).anyMatch(lower::contains)
                && !lower.endsWith("ref")
                && !lower.endsWith("kind")
                && !lower.endsWith("id");
    }

    /**
     * The actor's workspace as a UUID, or {@code null} when the actor has none or the value is not a
     * valid UUID. Never throws.
     */
    private static UUID workspaceOf(Actor actor) {
        if (actor.orgId() == null) {
            return null;
        }
        try {
            return UUID.fromString(actor.orgId());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Truncates text to {@code max} characters; {@code null} stays {@code null}. */
    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Maps blank text to {@code null} so an optional field is stored as absent rather than as an empty
     * string.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
