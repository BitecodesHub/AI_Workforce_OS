// @find: push notifications, web push service, subscribe device, unsubscribe device, my devices, send test push, notify approval raised push, notify question asked push, push enabled, PushService
// @what: Push notifications on a person's own devices: saves and removes subscriptions, sends the test, and tells the right people when an approval or a question is waiting.
// @flow: Called by PushController; hears LifecycleAnnouncer as a GoalLifecycleListener after an approval or question commits; chooses people with PushRecipients and delivers on its own threads through PushDelivery
package os.aiworkforce.orchestrator.push;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.push.PushSubscriptionStore.PushSubscription;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Tells a person's phone or laptop that something is waiting for them.
 *
 * <p>A person turns push on for one browser; that browser's subscription is saved against them and
 * their workspace. When an approval is raised, everyone with push on who could decide it now is
 * told, and when a run asks a question, the person it asks is told. Each notification is a sentence
 * and a link; what the agent wanted to send never leaves the workspace this way.
 *
 * <p>Nothing here can hold up the work it reports. The announcement arrives after the approval or
 * question has committed, is handed to a thread of its own at once, and every request to a push
 * service has short time limits. A person is told at most {@value #NOTIFY_LIMIT} times in {@value
 * #NOTIFY_WINDOW_MINUTES} minutes; the rest wait on the Approvals page.
 */
@Service
public class PushService implements GoalLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** The most devices one person keeps; the oldest goes when another is added. */
    public static final int MAX_DEVICES = 10;
    /** Notifications one person receives in a window. */
    static final int NOTIFY_LIMIT = 8;
    static final int NOTIFY_WINDOW_MINUTES = 5;
    /** Test notifications one person may send in a minute. */
    static final int TEST_LIMIT = 3;
    /** How long the test button waits for every device to answer. */
    static final Duration TEST_WAIT = Duration.ofSeconds(15);

    private static final int AUTH_SECRET_LENGTH = 16;
    private static final int KEY_TEXT_MAX = 200;

    /** Whether push can be offered, and the key a browser subscribes with. */
    public record Status(boolean enabled, String publicKey) {}

    /** How the test went: devices tried, how many took it, and how many were found gone and removed. */
    public record TestResult(int devices, int delivered, int removed, String message) {}

    private final PushSubscriptionStore store;
    private final VapidKeyStore keys;
    private final PushRecipients recipients;
    private final PushEndpoints endpoints;
    private final PushDelivery delivery;
    private final Executor executor;
    private final ExecutorService ownExecutor;
    private final boolean enabled;
    private final Clock clock;
    private final PushRateLimiter notifyLimiter;
    private final PushRateLimiter testLimiter;

    /** Spring's constructor: real HTTP delivery on virtual threads, the endpoint rules for this environment. */
    @Autowired
    public PushService(
            PushSubscriptionStore store,
            VapidKeyStore keys,
            PushRecipients recipients,
            ObjectMapper json,
            PlatformProperties properties,
            @Value("${aiwos.push.enabled:true}") boolean enabled,
            @Value("${aiwos.push.allowed-hosts:}") String extraHosts,
            @Value("${aiwos.push.subject:}") String subject,
            @Value("${aiwos.notifications.link-base-url:}") String linkBaseUrl) {
        this(store, keys, recipients, endpointsFor(extraHosts, properties), json, subjectFor(subject, linkBaseUrl, properties),
                new PushDelivery.HttpTransport(), Executors.newVirtualThreadPerTaskExecutor(), enabled, Clock.systemUTC(), true);
    }

    /** For tests: their own endpoint rules, transport, executor and clock. */
    PushService(
            PushSubscriptionStore store,
            VapidKeyStore keys,
            PushRecipients recipients,
            PushEndpoints endpoints,
            ObjectMapper json,
            String subject,
            PushDelivery.Transport transport,
            Executor executor,
            boolean enabled,
            Clock clock) {
        this(store, keys, recipients, endpoints, json, subject, transport, executor, enabled, clock, false);
    }

    // Shared constructor: builds the delivery and the two limiters, and remembers an executor it must shut down.
    private PushService(
            PushSubscriptionStore store,
            VapidKeyStore keys,
            PushRecipients recipients,
            PushEndpoints endpoints,
            ObjectMapper json,
            String subject,
            PushDelivery.Transport transport,
            Executor executor,
            boolean enabled,
            Clock clock,
            boolean ownsExecutor) {
        this.store = store;
        this.keys = keys;
        this.recipients = recipients;
        this.endpoints = endpoints;
        this.delivery = new PushDelivery(store, keys, endpoints, transport, json, subject, clock);
        this.executor = executor;
        this.ownExecutor = ownsExecutor && executor instanceof ExecutorService service ? service : null;
        this.enabled = enabled;
        this.clock = clock;
        this.notifyLimiter = new PushRateLimiter(NOTIFY_LIMIT, Duration.ofMinutes(NOTIFY_WINDOW_MINUTES), clock);
        this.testLimiter = new PushRateLimiter(TEST_LIMIT, Duration.ofMinutes(1), clock);
    }

    /** Stops the delivery threads, but only when this service created them. Called by Spring at shutdown. */
    @PreDestroy
    void shutdown() {
        if (ownExecutor != null) {
            ownExecutor.shutdown();
        }
    }

    // The default push services plus the operator's own; strict address checks in any deployed environment.
    private static PushEndpoints endpointsFor(String extraHosts, PlatformProperties properties) {
        return new PushEndpoints(PushEndpoints.hostsWith(extraHosts), properties.environment().isDeployed());
    }

    /**
     * The VAPID contact: the configured one, else {@code mailto:no-reply@<host>} for the host the app
     * is served from (the notification link base, else the first allowed web origin), else localhost.
     */
    static String subjectFor(String configured, String linkBaseUrl, PlatformProperties properties) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        String base = linkBaseUrl;
        if ((base == null || base.isBlank()) && properties != null) {
            List<String> origins = properties.http().corsAllowedOrigins();
            base = origins == null || origins.isEmpty() ? "" : origins.get(0);
        }
        String host = null;
        try {
            host = base == null || base.isBlank() ? null : URI.create(base.strip()).getHost();
        } catch (IllegalArgumentException notAnAddress) {
            host = null;
        }
        return "mailto:no-reply@" + (host == null || host.isBlank() ? "localhost" : host);
    }

    // ---- Settings page -------------------------------------------------------------------------

    // @find: is push available, VAPID public key for browser
    /** Whether push can be offered here, and the public key to subscribe with. May load or create the key pair. */
    public Status status() {
        if (!enabled) {
            return new Status(false, null);
        }
        Optional<String> publicKey = keys.publicKey();
        return new Status(publicKey.isPresent(), publicKey.orElse(null));
    }

    // @find: save push subscription, turn on push for this device
    /**
     * Saves this browser's subscription for the person, after checking everything that arrived
     * with it: the endpoint is a browser push service, the key is a P-256 point and the secret is
     * 16 bytes. The same browser subscribing again refreshes its row. A person keeps at most
     * {@value #MAX_DEVICES} devices; the oldest goes when another arrives.
     *
     * @throws ApiException validation for anything malformed; dependency degraded when push is off
     */
    public PushSubscription subscribe(UUID orgId, UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        requireEnabled();
        try {
            endpoints.check(endpoint);
        } catch (IllegalArgumentException refused) {
            throw ApiException.validation("endpoint", refused.getMessage());
        }
        if (p256dh == null || p256dh.isBlank() || p256dh.length() > KEY_TEXT_MAX) {
            throw ApiException.validation("keys.p256dh", "is missing or too long");
        }
        if (auth == null || auth.isBlank() || auth.length() > KEY_TEXT_MAX) {
            throw ApiException.validation("keys.auth", "is missing or too long");
        }
        String cleanKey;
        String cleanAuth;
        try {
            byte[] key = WebPushCrypto.fromBase64Url(p256dh);
            WebPushCrypto.publicKey(key);
            cleanKey = WebPushCrypto.base64Url(key);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.validation("keys.p256dh", "is not a browser push key");
        }
        try {
            byte[] secret = WebPushCrypto.fromBase64Url(auth);
            if (secret.length != AUTH_SECRET_LENGTH) {
                throw new IllegalArgumentException("wrong length");
            }
            cleanAuth = WebPushCrypto.base64Url(secret);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.validation("keys.auth", "is not a browser push secret");
        }
        PushSubscription saved =
                store.upsert(orgId, userId, endpoint.strip(), cleanKey, cleanAuth, DeviceLabels.fromUserAgent(userAgent));
        store.trimOldest(orgId, userId, MAX_DEVICES);
        return saved;
    }

    // @find: remove push subscription, turn off push for this device
    /** Removes the person's own subscription with this endpoint. @return the removed one's id, or empty when there was none */
    public Optional<UUID> unsubscribe(UUID orgId, UUID userId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw ApiException.validation("endpoint", "is missing");
        }
        return store.deleteOwned(orgId, userId, endpoint.strip());
    }

    /** The person's own devices in this workspace, newest first. Read-only. */
    public List<PushSubscription> devices(UUID orgId, UUID userId) {
        return store.listFor(orgId, userId);
    }

    // @find: send a test notification
    /**
     * Sends the test notification to every device the person has, waits up to {@code TEST_WAIT}
     * for the answers and says how it went. At most {@value #TEST_LIMIT} a minute.
     *
     * @throws ApiException rate limited beyond the limit; dependency degraded when push is off
     */
    public TestResult sendTest(UUID orgId, UUID userId) {
        requireEnabled();
        if (!testLimiter.tryAcquire(userId)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Wait a minute before sending another test notification.");
        }
        List<PushSubscription> devices = store.listFor(orgId, userId);
        if (devices.isEmpty()) {
            return new TestResult(0, 0, 0, "No device has push notifications turned on yet.");
        }
        PushMessage message = PushMessage.test();
        List<CompletableFuture<PushDelivery.Outcome>> sends = new ArrayList<>();
        for (PushSubscription device : devices) {
            sends.add(CompletableFuture.supplyAsync(() -> delivery.deliver(device, message), executor));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException slow) {
            log.debug("Not every test notification answered in time: {}", slow.toString());
        }
        int delivered = 0;
        int removed = 0;
        for (CompletableFuture<PushDelivery.Outcome> send : sends) {
            // Still waiting, or failed outright, counts as not delivered.
            PushDelivery.Outcome outcome = send.isDone() && !send.isCompletedExceptionally()
                    ? send.join()
                    : PushDelivery.Outcome.FAILED;
            if (outcome == PushDelivery.Outcome.DELIVERED) {
                delivered++;
            } else if (outcome == PushDelivery.Outcome.GONE) {
                removed++;
            }
        }
        return new TestResult(devices.size(), delivered, removed, testSentence(devices.size(), delivered, removed));
    }

    // One sentence for the settings page about how the test went.
    static String testSentence(int devices, int delivered, int removed) {
        if (delivered == devices) {
            return devices == 1
                    ? "The test notification was sent to your device."
                    : "The test notification was sent to all " + devices + " of your devices.";
        }
        StringBuilder text = new StringBuilder(
                delivered == 0 ? "The test notification could not be sent." : "The test notification was sent to "
                        + delivered + " of your " + devices + " devices.");
        if (removed > 0) {
            text.append(removed == 1
                    ? " One device no longer accepts notifications and was removed."
                    : " " + removed + " devices no longer accept notifications and were removed.");
        }
        return text.toString();
    }

    // Refuses when push cannot be offered: switched off, or no key pair.
    private void requireEnabled() {
        if (!status().enabled()) {
            throw new ApiException(ErrorCode.DEPENDENCY_DEGRADED, "Push notifications are not available on this server.");
        }
    }

    // ---- What the engine announces --------------------------------------------------------------

    // @find: push when approval raised
    /**
     * An approval is waiting: everyone with push on who may decide it is told. Returns at once; the
     * people are chosen and the messages sent on a delivery thread. Never throws into the caller.
     */
    @Override
    public void onApprovalRaised(Goal goal, Task task, Approval approval) {
        if (!enabled || approval == null) {
            return;
        }
        queue(() -> {
            List<UUID> people = recipients.forApproval(approval);
            send(approval.getOrgId(), people, PushMessage.forApproval(approval, clock.instant()));
        }, "approval " + approval.getId());
    }

    // @find: push when question asked
    /** A run asked its requester a question: that person is told. Returns at once, like {@link #onApprovalRaised}. */
    @Override
    public void onQuestionAsked(Goal goal, Task task, RunQuestion question) {
        if (!enabled || question == null) {
            return;
        }
        queue(() -> {
            List<UUID> people = recipients.forQuestion(question);
            send(question.getOrgId(), people, PushMessage.forQuestion(question, clock.instant()));
        }, "question " + question.getId());
    }

    /**
     * Hands work to a delivery thread; a refusal to queue is logged, never thrown. The work runs
     * as the platform itself: the thread that raised the approval may be acting for a person (an
     * approver whose decision resumed the run), and the lookups push makes are the platform's own,
     * which the identity service grants to a service and refuses to a person's token.
     */
    private void queue(Runnable work, String what) {
        try {
            executor.execute(() -> {
                try {
                    RequestContext.as(Actor.SYSTEM, () -> {
                        work.run();
                        return null;
                    });
                } catch (RuntimeException e) {
                    log.warn("Push notifications for {} could not be sent: {}", what, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Push notifications for {} could not be queued: {}", what, e.getMessage());
        }
    }

    /**
     * Sends one message to every device of the given people who are still under their limit, each
     * device on a delivery thread of its own. Runs on a delivery thread.
     */
    void send(UUID orgId, List<UUID> people, PushMessage message) {
        if (people.isEmpty() || keys.keys().isEmpty()) {
            return;
        }
        List<UUID> allowed = new ArrayList<>();
        for (UUID person : people) {
            if (notifyLimiter.tryAcquire(person)) {
                allowed.add(person);
            } else {
                log.debug("Push to person {} held back: limit of {} in {} minutes reached", person, NOTIFY_LIMIT, NOTIFY_WINDOW_MINUTES);
            }
        }
        for (PushSubscription device : store.listForUsers(orgId, allowed)) {
            try {
                executor.execute(() -> delivery.deliver(device, message));
            } catch (RuntimeException e) {
                log.warn("Push to subscription {} could not be queued: {}", device.id(), e.getMessage());
            }
        }
    }
}
