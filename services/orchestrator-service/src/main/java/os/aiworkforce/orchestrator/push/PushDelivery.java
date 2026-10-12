// @find: send web push, deliver push notification, push service request, 404 410 remove subscription, push failure count, VAPID header, TTL Urgency headers, PushDelivery
// @what: Delivers one notification to one subscription: checks the endpoint, encrypts, signs, posts with time limits, and keeps the subscription's record (success, failure, gone).
// @flow: Used by PushService on its delivery threads; uses WebPushCrypto, VapidKeyStore, PushEndpoints and PushSubscriptionStore
package os.aiworkforce.orchestrator.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;

/**
 * One notification to one device.
 *
 * <p>The push service answers {@code 201} when it has taken the message. {@code 404} and {@code 410}
 * mean the browser dropped the subscription (the person turned notifications off, cleared the site
 * or uninstalled the browser), so the row is removed at once. Any other answer, or none, counts as
 * a failure; a subscription that fails {@value #MAX_FAILURES} times in a row is removed too, so a
 * device that will never answer is not tried forever. Nothing is retried: the Approvals badge and
 * the queue still show what is waiting, and a push that arrives late is worse than none.
 *
 * <p>Every request has a five-second connect limit and a ten-second answer limit, follows no
 * redirect, and goes only to an endpoint {@link PushEndpoints} allows, checked again here because
 * a host name can change what it points at after the subscription was saved.
 */
public class PushDelivery {

    private static final Logger log = LoggerFactory.getLogger(PushDelivery.class);

    /** Failures in a row after which a subscription is removed. */
    public static final int MAX_FAILURES = 10;

    /** How long a VAPID token is made to last; push services accept up to a day. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    /** A cached token is replaced this long before it would expire. */
    private static final Duration TOKEN_RENEWAL = Duration.ofHours(1);
    private static final int MAX_CACHED_TOKENS = 64;

    /** How one delivery ended. */
    public enum Outcome {
        /** The push service took the message. */
        DELIVERED,
        /** The subscription is gone, and was removed. */
        GONE,
        /** It did not arrive; the failure was counted. */
        FAILED,
        /** It was not attempted: push is off, or the endpoint is not allowed. */
        SKIPPED
    }

    /** Posts a body to a push service and says what status answered; anything that is not an answer throws. */
    @FunctionalInterface
    public interface Transport {
        int post(URI endpoint, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
    }

    private record CachedToken(String header, Instant renewAt) {}

    private final PushSubscriptionStore store;
    private final VapidKeyStore keys;
    private final PushEndpoints endpoints;
    private final Transport transport;
    private final ObjectMapper json;
    private final String subject;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    /**
     * @param subject the VAPID contact, a {@code mailto:} or {@code https:} address
     */
    public PushDelivery(
            PushSubscriptionStore store,
            VapidKeyStore keys,
            PushEndpoints endpoints,
            Transport transport,
            ObjectMapper json,
            String subject,
            Clock clock) {
        this.store = store;
        this.keys = keys;
        this.endpoints = endpoints;
        this.transport = transport;
        this.json = json;
        this.subject = subject;
        this.clock = clock;
    }

    // @find: deliver one push
    /**
     * Sends {@code message} to {@code subscription} and records how it went.
     *
     * <p>Runs on the caller's thread and may take up to the request limits. Writes to the store:
     * success time, failure count, or removal. Never throws; an interrupted send restores the
     * interrupt flag and reports {@link Outcome#FAILED}.
     */
    public Outcome deliver(PushSubscription subscription, PushMessage message) {
        var pair = keys.keys();
        if (pair.isEmpty()) {
            return Outcome.SKIPPED;
        }
        URI endpoint;
        try {
            endpoint = endpoints.check(subscription.endpoint());
        } catch (IllegalArgumentException refused) {
            // Saved under a rule that no longer allows it (an operator narrowed the list): it will
            // never be allowed again, so it goes.
            log.warn("Removing push subscription {}: its endpoint {}", subscription.id(), refused.getMessage());
            store.delete(subscription.id());
            return Outcome.GONE;
        }
        int status;
        try {
            byte[] body = WebPushCrypto.encrypt(
                    payload(message),
                    WebPushCrypto.fromBase64Url(subscription.p256dh()),
                    WebPushCrypto.fromBase64Url(subscription.auth()));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("TTL", Long.toString(message.ttlSeconds()));
            headers.put("Content-Encoding", "aes128gcm");
            headers.put("Content-Type", "application/octet-stream");
            headers.put("Urgency", "test".equals(message.kind()) ? "normal" : "high");
            headers.put("Authorization", authorization(endpoint, pair.get()));
            status = transport.post(endpoint, headers, body);
        } catch (IllegalArgumentException unusable) {
            // The stored keys cannot encrypt anything: the subscription is broken for good.
            log.warn("Removing push subscription {}: {}", subscription.id(), unusable.getMessage());
            store.delete(subscription.id());
            return Outcome.GONE;
        } catch (IOException unreachable) {
            return failed(subscription, "no answer (" + unreachable.getClass().getSimpleName() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(subscription, "interrupted");
        } catch (RuntimeException unexpected) {
            return failed(subscription, unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        }

        if (status >= 200 && status < 300) {
            store.recordSuccess(subscription.id(), clock.instant());
            return Outcome.DELIVERED;
        }
        if (status == 404 || status == 410) {
            store.delete(subscription.id());
            return Outcome.GONE;
        }
        return failed(subscription, "the push service answered with status " + status);
    }

    // Counts a failure, removes a subscription that has failed too often, and says so in the log.
    private Outcome failed(PushSubscription subscription, String why) {
        int count;
        try {
            count = store.recordFailure(subscription.id());
        } catch (RuntimeException storeDown) {
            log.warn("Push to subscription {} failed ({}), and the failure could not be counted", subscription.id(), why);
            return Outcome.FAILED;
        }
        if (count >= MAX_FAILURES) {
            store.delete(subscription.id());
            log.warn("Removed push subscription {} after {} failures in a row; the last: {}", subscription.id(), count, why);
        } else {
            // The endpoint is left out: it is the capability to reach the device.
            log.warn("Push to subscription {} failed ({} in a row): {}", subscription.id(), count, why);
        }
        return Outcome.FAILED;
    }

    // The message as the JSON the service worker reads, cut to what one record can hold.
    private byte[] payload(PushMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", message.kind());
        fields.put("title", message.title());
        fields.put("body", message.body());
        fields.put("tag", message.tag());
        fields.put("url", message.url());
        try {
            byte[] bytes = json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > WebPushCrypto.MAX_PLAINTEXT) {
                fields.put("body", "Open the app to see what is waiting.");
                bytes = json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
            }
            return bytes;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A push message could not be written as JSON", e);
        }
    }

    // The VAPID Authorization header for this push service, reusing a token until it nears expiry.
    private String authorization(URI endpoint, WebPushCrypto.RawKeyPair pair) {
        String audience = WebPushCrypto.audienceOf(endpoint);
        Instant now = clock.instant();
        CachedToken cached = tokens.get(audience);
        if (cached != null && now.isBefore(cached.renewAt())) {
            return cached.header();
        }
        Instant expires = now.plus(TOKEN_LIFETIME);
        String token = WebPushCrypto.vapidToken(audience, expires.getEpochSecond(), subject, pair.privateKey());
        String header = WebPushCrypto.vapidAuthorization(token, pair.publicKey());
        if (tokens.size() >= MAX_CACHED_TOKENS) {
            tokens.clear();
        }
        tokens.put(audience, new CachedToken(header, expires.minus(TOKEN_RENEWAL)));
        return header;
    }

    /** The production transport: the JDK's HTTP client, no redirects, five-second connect and ten-second answer limits. */
    public static final class HttpTransport implements Transport {

        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        /** Posts the encrypted body with the given headers and returns the status code. */
        @Override
        public int post(URI endpoint, Map<String, String> headers, byte[] body) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header);
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
