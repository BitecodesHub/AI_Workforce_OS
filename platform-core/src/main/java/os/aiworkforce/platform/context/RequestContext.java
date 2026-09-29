package os.aiworkforce.platform.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Ambient context for the unit of work in flight.
 *
 * <p>Every log line, audit event, outbound call and emitted message needs the same few facts:
 * who is acting, in which organisation, under which request and trace. Threading them through
 * every method signature would drown the code, so they live here and are set once by a filter.
 *
 * <p>The holder is inheritable so that work handed to an executor keeps its context. Anything
 * that crosses a thread boundary without inheritance - a Kafka consumer, a scheduled job - is
 * expected to rebuild the context from an event envelope through {@link #restore(Snapshot)}.
 * {@link #clear()} in a {@code finally} block is mandatory on pooled threads, otherwise one
 * request's identity leaks into the next.
 */
public final class RequestContext {

    private static final ThreadLocal<State> HOLDER = new InheritableThreadLocal<>() {
        @Override
        protected State childValue(State parent) {
            return parent == null ? null : parent.copy();
        }
    };

    private RequestContext() {}

    /** Mutable per-thread state. Copied rather than shared when a child thread inherits it. */
    private static final class State {
        private String requestId;
        private String traceId;
        private Actor actor;
        private String idempotencyKey;
        private final Map<String, String> attributes = new HashMap<>(4);

        State copy() {
            State s = new State();
            s.requestId = requestId;
            s.traceId = traceId;
            s.actor = actor;
            s.idempotencyKey = idempotencyKey;
            s.attributes.putAll(attributes);
            return s;
        }
    }

    /** Serialisable context, for an event envelope or a job payload. */
    public record Snapshot(
            String requestId, String traceId, String idempotencyKey, Actor actor, Map<String, String> attributes) {}

    private static State state() {
        State s = HOLDER.get();
        if (s == null) {
            s = new State();
            HOLDER.set(s);
        }
        return s;
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String requestId() {
        String id = state().requestId;
        if (id == null) {
            id = newRequestId();
            state().requestId = id;
        }
        return id;
    }

    public static void setRequestId(String requestId) {
        state().requestId = requestId;
    }

    public static Optional<String> traceId() {
        return Optional.ofNullable(state().traceId);
    }

    public static void setTraceId(String traceId) {
        state().traceId = traceId;
    }

    public static Optional<String> idempotencyKey() {
        return Optional.ofNullable(state().idempotencyKey);
    }

    public static void setIdempotencyKey(String key) {
        state().idempotencyKey = key;
    }

    public static Optional<Actor> actor() {
        return Optional.ofNullable(state().actor);
    }

    public static void setActor(Actor actor) {
        state().actor = actor;
    }

    /**
     * The acting principal, or a 401 when there is none.
     *
     * <p>Call this from anything that must not run anonymously. Returning an empty optional and
     * letting a caller forget to check is how anonymous work reaches a database.
     */
    public static Actor requireActor() {
        Actor actor = state().actor;
        if (actor == null) {
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }
        return actor;
    }

    /** The organisation in scope, or a 401 when the caller has no organisation. */
    public static String requireOrgId() {
        Actor actor = requireActor();
        if (actor.orgId() == null) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "No workspace is selected for this session.");
        }
        return actor.orgId();
    }

    public static Optional<String> orgId() {
        return actor().map(Actor::orgId);
    }

    public static void setAttribute(String key, String value) {
        state().attributes.put(key, value);
    }

    public static Optional<String> attribute(String key) {
        return Optional.ofNullable(state().attributes.get(key));
    }

    public static Snapshot snapshot() {
        State s = state();
        return new Snapshot(s.requestId, s.traceId, s.idempotencyKey, s.actor, Map.copyOf(s.attributes));
    }

    public static void restore(Snapshot snapshot) {
        State s = state();
        s.requestId = snapshot.requestId();
        s.traceId = snapshot.traceId();
        s.idempotencyKey = snapshot.idempotencyKey();
        s.actor = snapshot.actor();
        s.attributes.clear();
        if (snapshot.attributes() != null) {
            s.attributes.putAll(snapshot.attributes());
        }
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** Runs {@code body} under {@code snapshot}, restoring whatever was there before. */
    public static <T> T with(Snapshot snapshot, Supplier<T> body) {
        Snapshot previous = snapshot();
        try {
            restore(snapshot);
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** Runs {@code body} as a named actor, restoring the previous actor afterwards. */
    public static <T> T as(Actor actor, Supplier<T> body) {
        Actor previous = state().actor;
        try {
            state().actor = actor;
            return body.get();
        } finally {
            state().actor = previous;
        }
    }

    /** Wraps a task so it carries the current context onto another thread. */
    public static Runnable wrap(Runnable task) {
        Snapshot snapshot = snapshot();
        return () -> {
            Snapshot previous = snapshot();
            try {
                restore(snapshot);
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        Snapshot snapshot = snapshot();
        return () -> {
            Snapshot previous = snapshot();
            try {
                restore(snapshot);
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }
}
