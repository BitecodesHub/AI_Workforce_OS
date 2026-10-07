package os.aiworkforce.orchestrator.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

/**
 * Tells a workspace, outside the app, that something is waiting for it.
 *
 * <p>An approval that a schedule raises at six in the morning sits unseen until somebody opens the
 * page, and expires after a day if nobody does; a schedule that breaks over a weekend pauses itself
 * in silence. Both are told to one place the workspace chooses: a webhook address, which reaches
 * Slack, Teams, Zapier or a script alike, and is the workspace's own rather than any agent's tool
 * grant. Nothing here depends on a connector, on an agent's permissions, or on an approval policy:
 * a system alert must not wait on the thing it reports.
 *
 * <p>Each message carries only a sentence and a link back into the app. It never carries what the
 * agent was about to send: tool arguments hold customers' names and messages, and a message that
 * leaves the workspace must not become another way for them to. When the workspace gave a secret,
 * the body is signed with it, in the header {@code X-Signature: sha256=<hex>}, so a receiver can tell
 * a real message from one anybody could post to its address. The secret is kept encrypted.
 *
 * <p>Delivery is off the caller's thread: the listener hands the message to a thread of its own and
 * returns, so a receiver that is slow or down cannot hold up the approval or the run that raised
 * it. A message that does not arrive is tried twice more, then logged at WARN and dropped.
 *
 * <p>The address is checked, because a workspace's own administrators choose it and this service
 * makes the request: a deployed workspace must use https to a public address, never to one inside
 * the network the service runs in, and a redirect is never followed.
 */
@Service
public class NotificationService implements GoalLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String APPROVAL_RAISED = "approvalRaised";
    public static final String APPROVAL_EXPIRED = "approvalExpired";
    public static final String SCHEDULE_PAUSED = "schedulePaused";
    /** What the Send test button sends; never offered as a subscription. */
    public static final String TEST = "test";

    /** Every event a workspace can choose to hear, in the order the settings page lists them. */
    public static final List<String> EVENTS = List.of(APPROVAL_RAISED, APPROVAL_EXPIRED, SCHEDULE_PAUSED);

    static final ConfigKey WEBHOOK_URL = ConfigKey.text(
            "notifications.webhookUrl",
            ConfigKey.Scope.WORKSPACE,
            "",
            "Where the workspace is told that an approval is waiting or a schedule paused itself."
                    + " Empty means nowhere.");

    /** Holds the secret as {@code EnvelopeEncryptionService} serialises it, never in clear. */
    static final ConfigKey WEBHOOK_SECRET = ConfigKey.text(
                    "notifications.webhookSecret",
                    ConfigKey.Scope.WORKSPACE,
                    "",
                    "Signs each message so the receiver can tell it came from this workspace.")
            .asSensitive();

    /**
     * A comma-separated text, not a list key: the platform renders a list default as its Java
     * {@code toString}, which no reader of the setting would parse back.
     */
    static final ConfigKey EVENT_LIST = ConfigKey.text(
            "notifications.events",
            ConfigKey.Scope.WORKSPACE,
            String.join(",", EVENTS),
            "Which events are sent: approvalRaised, approvalExpired and schedulePaused.");

    public static final int SECRET_MIN = 8;
    public static final int SECRET_MAX = 256;
    private static final int URL_MAX = 2_000;
    private static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** What a workspace has set up, as the settings page reads it. The secret is never part of it. */
    public record Settings(String webhookUrl, boolean hasSecret, List<String> events) {}

    /** How the Send test button went, in terms a person can act on. */
    public record TestResult(boolean delivered, Integer statusCode, String message) {}

    /** The message as the receiver gets it: a sentence and a link, and nothing the agent was doing. */
    record Message(String event, UUID workspaceId, String summary, String link) {}

    /** Posts a body to an address and says what status answered; anything that is not an answer throws. */
    @FunctionalInterface
    interface Sender {
        int post(URI target, String body, String signature) throws IOException, InterruptedException;
    }

    private final RuntimeConfigService config;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper json;
    private final boolean strict;
    private final String linkBase;
    private final Sender sender;
    private final Executor executor;
    private final List<Duration> retryDelays;
    private final ExecutorService ownExecutor;

    @Autowired
    public NotificationService(
            RuntimeConfigService config,
            EnvelopeEncryptionService encryption,
            PlatformProperties properties,
            ObjectMapper json,
            @Value("${aiwos.notifications.link-base-url:}") String linkBaseUrl) {
        this(
                config,
                encryption,
                json,
                properties.environment().isDeployed(),
                linkBase(linkBaseUrl, properties),
                new HttpSender(),
                Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_RETRY_DELAYS,
                true);
    }

    /** For tests: a sender, an executor and retry delays of their own. */
    NotificationService(
            RuntimeConfigService config,
            EnvelopeEncryptionService encryption,
            ObjectMapper json,
            boolean strict,
            String linkBase,
            Sender sender,
            Executor executor,
            List<Duration> retryDelays) {
        this(config, encryption, json, strict, linkBase, sender, executor, retryDelays, false);
    }

    private NotificationService(
            RuntimeConfigService config,
            EnvelopeEncryptionService encryption,
            ObjectMapper json,
            boolean strict,
            String linkBase,
            Sender sender,
            Executor executor,
            List<Duration> retryDelays,
            boolean ownsExecutor) {
        this.config = config;
        this.encryption = encryption;
        this.json = json;
        this.strict = strict;
        this.linkBase = linkBase;
        this.sender = sender;
        this.executor = executor;
        this.ownExecutor = ownsExecutor && executor instanceof ExecutorService service ? service : null;
        this.retryDelays = List.copyOf(retryDelays);
        config.register(List.of(WEBHOOK_URL, WEBHOOK_SECRET, EVENT_LIST));
    }

    @PreDestroy
    void shutdown() {
        if (ownExecutor != null) {
            ownExecutor.shutdown();
        }
    }

    private static String linkBase(String configured, PlatformProperties properties) {
        String base = configured;
        if (base == null || base.isBlank()) {
            List<String> origins = properties.http().corsAllowedOrigins();
            base = origins == null || origins.isEmpty() ? "" : origins.get(0);
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // ---- Settings -----------------------------------------------------------------------------

    /** What this workspace has set up. */
    public Settings settings(UUID orgId) {
        String org = orgId.toString();
        return new Settings(
                config.getString(WEBHOOK_URL, org),
                !config.getString(WEBHOOK_SECRET, org).isBlank(),
                eventsOf(config.getString(EVENT_LIST, org)));
    }

    /** How a change to the secret is described afterwards, for the audit entry. */
    public enum SecretChange {
        KEPT,
        SET,
        REMOVED
    }

    /**
     * Saves a workspace's webhook.
     *
     * @param webhookUrl where messages go; empty turns notifications off and removes the secret
     * @param secret a new secret to sign with; null keeps the one already stored, empty removes it
     * @param events the events to send; every one must be a known event
     * @return how the secret changed
     */
    public SecretChange update(UUID orgId, String webhookUrl, String secret, List<String> events, String actorId) {
        String org = orgId.toString();
        String url = webhookUrl == null ? "" : webhookUrl.strip();
        if (!url.isEmpty()) {
            try {
                checkTarget(url, strict);
            } catch (IllegalArgumentException problem) {
                throw ApiException.validation("webhookUrl", problem.getMessage());
            }
        }
        List<String> chosen = events == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(events));
        for (String event : chosen) {
            if (!EVENTS.contains(event)) {
                throw ApiException.validation("events", "includes " + event + ", which is not an event this can send");
            }
        }
        if (secret != null && !secret.isEmpty() && (secret.length() < SECRET_MIN || secret.length() > SECRET_MAX)) {
            throw ApiException.validation(
                    "secret", "must be between " + SECRET_MIN + " and " + SECRET_MAX + " characters");
        }

        SecretChange change = SecretChange.KEPT;
        if (url.isEmpty()) {
            // No address means nothing to sign for: the secret goes with it.
            config.clear(WEBHOOK_URL.name(), org, actorId);
            if (!config.getString(WEBHOOK_SECRET, org).isBlank()) {
                change = SecretChange.REMOVED;
            }
            config.clear(WEBHOOK_SECRET.name(), org, actorId);
        } else {
            config.set(WEBHOOK_URL.name(), org, url, actorId);
            if (secret != null && secret.isEmpty()) {
                config.clear(WEBHOOK_SECRET.name(), org, actorId);
                change = SecretChange.REMOVED;
            } else if (secret != null) {
                config.set(WEBHOOK_SECRET.name(), org, encryption.encrypt(org, secret).serialise(), actorId);
                change = SecretChange.SET;
            }
        }
        config.set(EVENT_LIST.name(), org, String.join(",", chosen), actorId);
        return change;
    }

    private static List<String> eventsOf(String stored) {
        List<String> events = new ArrayList<>();
        if (stored != null) {
            for (String part : stored.split(",")) {
                String event = part.strip();
                if (EVENTS.contains(event) && !events.contains(event)) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    // ---- Sending ------------------------------------------------------------------------------

    /**
     * Sends one test message now and says how it went, so a person setting this up learns at once
     * whether it works. One attempt, no retries: the person is waiting for the answer.
     */
    public TestResult sendTest(UUID orgId) {
        String org = orgId.toString();
        String url = config.getString(WEBHOOK_URL, org);
        if (url == null || url.isBlank()) {
            return new TestResult(false, null, "No webhook address is saved yet.");
        }
        Message message = new Message(TEST, orgId, "This is a test message from AI Workforce OS.", link("/settings"));
        try {
            int status = attempt(orgId, url, message);
            if (status >= 200 && status < 300) {
                return new TestResult(true, status, "The test message was delivered.");
            }
            return new TestResult(
                    false, status, "The address answered with status " + status + ", so it was not accepted.");
        } catch (IllegalArgumentException refused) {
            return new TestResult(false, null, "That address cannot be used: " + refused.getMessage() + ".");
        } catch (IOException unreachable) {
            return new TestResult(false, null, "Nothing answered at that address.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TestResult(false, null, "The test was interrupted. Try again.");
        } catch (RuntimeException unreadable) {
            log.warn("Test notification for workspace {} could not be sent: {}", orgId, unreadable.getMessage());
            return new TestResult(false, null, "The test message could not be sent. Check the saved settings.");
        }
    }

    /** One try: checks the address, signs the body and posts it. Returns the status that answered. */
    private int attempt(UUID orgId, String url, Message message) throws IOException, InterruptedException {
        // Checked again at every attempt: a name that pointed somewhere public when it was saved may not now.
        URI target = checkTarget(url, strict);
        String body;
        try {
            body = json.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A notification could not be written as JSON", e);
        }
        return sender.post(target, body, signature(orgId, body));
    }

    /** {@code sha256=<hex>} over the exact body, or null when the workspace gave no secret. */
    private String signature(UUID orgId, String body) {
        String stored = config.getString(WEBHOOK_SECRET, orgId.toString());
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String secret = encryption.decrypt(orgId.toString(), stored);
        return "sha256=" + hmacHex(secret, body);
    }

    static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }

    /** Hands a message to a thread of its own, and never throws into the caller. */
    private void publish(UUID orgId, String event, String summary, String path) {
        Message message = new Message(event, orgId, summary, link(path));
        try {
            executor.execute(() -> deliver(orgId, message));
        } catch (RuntimeException e) {
            log.warn("Notification {} for workspace {} could not be queued: {}", event, orgId, e.getMessage());
        }
    }

    /** Sends a message if the workspace asked to hear it, trying twice more after a failure. */
    void deliver(UUID orgId, Message message) {
        String failure = "no attempt was made";
        int attempts = 0;
        try {
            String org = orgId.toString();
            String url = config.getString(WEBHOOK_URL, org);
            if (url == null
                    || url.isBlank()
                    || !eventsOf(config.getString(EVENT_LIST, org)).contains(message.event())) {
                return;
            }
            for (int retry = 0; retry <= retryDelays.size(); retry++) {
                if (retry > 0) {
                    sleep(retryDelays.get(retry - 1));
                }
                attempts++;
                try {
                    int status = attempt(orgId, url, message);
                    if (status >= 200 && status < 300) {
                        return;
                    }
                    failure = "the address answered with status " + status;
                } catch (IOException e) {
                    failure = "no answer (" + e.getClass().getSimpleName() + ")";
                } catch (IllegalArgumentException e) {
                    // The address is not allowed: another attempt would not change that.
                    failure = e.getMessage();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrupted";
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // The address is left out: a webhook address often carries its own token.
        log.warn(
                "Notification {} for workspace {} was not delivered after {} attempt(s): {}",
                message.event(),
                orgId,
                attempts,
                failure);
    }

    private static void sleep(Duration delay) throws InterruptedException {
        if (!delay.isZero() && !delay.isNegative()) {
            Thread.sleep(delay);
        }
    }

    private String link(String path) {
        return linkBase + path;
    }

    // ---- What the engine announces --------------------------------------------------------------

    @Override
    public void onApprovalRaised(Goal goal, Task task, Approval approval) {
        publish(
                approval.getOrgId(),
                APPROVAL_RAISED,
                raisedSummary(approval),
                "/approvals#approval-" + approval.getId());
    }

    @Override
    public void onApprovalExpired(Goal goal, Task task, Approval approval) {
        publish(
                approval.getOrgId(),
                APPROVAL_EXPIRED,
                "An approval expired before anybody decided it, so its run was stopped and nothing was sent.",
                "/approvals?tab=decided#approval-" + approval.getId());
    }

    @Override
    public void onSchedulePaused(SchedulePause pause) {
        publish(
                pause.orgId(),
                SCHEDULE_PAUSED,
                "A schedule paused itself after " + pause.failures() + " failed runs in a row.",
                "/schedules");
    }

    /**
     * What an agent is waiting to do, from what kind of action it is and which tool it is: words
     * the platform wrote, never the arguments or the goal's own title, which can hold anyone's name.
     */
    static String raisedSummary(Approval approval) {
        String does =
                switch (approval.getActionClass() == null ? "" : approval.getActionClass().toUpperCase()) {
                    case "DESTRUCTIVE" -> "remove something";
                    case "WRITE" -> "change something";
                    default -> "send something outside the workspace";
                };
        String tool = approval.getTool() == null || approval.getTool().isBlank() ? "" : " using " + approval.getTool();
        return "An agent is waiting for approval to " + does + tool + ".";
    }

    // ---- The address --------------------------------------------------------------------------

    /**
     * Checks that {@code url} is somewhere this service may post to.
     *
     * <p>When {@code strict} - in any deployed environment - only https, to an address that
     * resolves to nothing inside a network: not loopback, link-local (where cloud metadata
     * lives), private, carrier-grade, or multicast. Locally, plain http and local addresses are
     * allowed, so a developer can point it at a receiver on their own machine.
     *
     * @throws IllegalArgumentException with what is wrong, as a phrase that follows "webhookUrl"
     */
    static URI checkTarget(String url, boolean strict) {
        if (url.length() > URL_MAX) {
            throw new IllegalArgumentException("is too long");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("is not a valid web address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean allowed = scheme.equals("https") || (!strict && scheme.equals("http"));
        if (!allowed) {
            throw new IllegalArgumentException(
                    strict ? "must start with https://" : "must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("must include a host name");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("must not contain a user name or password");
        }
        if (strict) {
            InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(uri.getHost());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("is a host name that could not be found");
            }
            for (InetAddress address : resolved) {
                if (isInternal(address)) {
                    throw new IllegalArgumentException("must be a public address, not one inside a private network");
                }
            }
        }
        return uri;
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 0.0.0.0/8 and carrier-grade NAT, 100.64.0.0/10.
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        // Unique-local IPv6, fc00::/7, which Java does not call site-local.
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** The sender the service uses, for a test that wants a real connection. */
    static Sender httpSender() {
        return new HttpSender();
    }

    /** The JDK client: no redirects, short timeouts, a body and a content type. */
    private static final class HttpSender implements Sender {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public int post(URI target, String body, String signature) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "AIWorkforceOS-Notifications/1")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (signature != null) {
                request.header("X-Signature", signature);
            }
            // The answer's body is never read: a receiver's reply is none of this service's business.
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }
}
