// @find: request context, current actor, request id, trace id, thread local, current organisation, workspace from token
// @what: Holds the current actor, workspace and request identifiers for the running request.
// @flow: Set by ActorContextFilter and JwtActorConverter; read by services and controllers
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

    /** Returns the thread-local state, creating it on first use. */
    private static State state() {
        State s = HOLDER.get();
        if (s == null) {
            s = new State();
            HOLDER.set(s);
        }
        return s;
    }

    /** Generates a new request identifier: a random UUID without hyphens. */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Returns the current request identifier, generating and storing one if none was set. */
    public static String requestId() {
        String id = state().requestId;
        if (id == null) {
            id = newRequestId();
            state().requestId = id;
        }
        return id;
    }

    /** Sets the request identifier, normally copied from the incoming correlation header by the request filter. */
    public static void setRequestId(String requestId) {
        state().requestId = requestId;
    }

    /** Returns the distributed trace identifier, if tracing supplied one. */
    public static Optional<String> traceId() {
        return Optional.ofNullable(state().traceId);
    }

    /** Records the distributed trace identifier for this thread. */
    public static void setTraceId(String traceId) {
        state().traceId = traceId;
    }

    /** Returns the client idempotency key of the current request, if any. */
    public static Optional<String> idempotencyKey() {
        return Optional.ofNullable(state().idempotencyKey);
    }

    /** Records the idempotency key supplied by the client for this request. */
    public static void setIdempotencyKey(String key) {
        state().idempotencyKey = key;
    }

    /** Returns the authenticated principal, or empty for an anonymous request. */
    public static Optional<Actor> actor() {
        return Optional.ofNullable(state().actor);
    }

    /** Binds the authenticated principal to this thread; called by the request filter after the token is verified. */
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

    /**
     * The organisation in scope.
     *
     * <p>Fails with a 401 {@code not_authenticated} when there is no actor, and with a 403
     * {@code organisation_mismatch} when the actor holds no organisation, as with a platform-level
     * principal that has not selected a workspace.
     */
    public static String requireOrgId() {
        Actor actor = requireActor();
        if (actor.orgId() == null) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH, "No workspace is selected for this session.");
        }
        return actor.orgId();
    }

    /** Returns the organisation of the current actor, if there is an actor. */
    public static Optional<String> orgId() {
        return actor().map(Actor::orgId);
    }

    /** Stores a free-form string attribute for the duration of the request. */
    public static void setAttribute(String key, String value) {
        state().attributes.put(key, value);
    }

    /** Reads a free-form attribute stored with {@link #setAttribute}. */
    public static Optional<String> attribute(String key) {
        return Optional.ofNullable(state().attributes.get(key));
    }

    /** Captures an immutable copy of the current context so it can be carried to another thread. */
    public static Snapshot snapshot() {
        State s = state();
        return new Snapshot(s.requestId, s.traceId, s.idempotencyKey, s.actor, Map.copyOf(s.attributes));
    }

    /** Replaces the current thread context with the snapshot contents. Pair with {@link #clear()} or {@link #with} so the context never leaks to the next task on a pooled thread. */
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

    /** Removes the thread-local state; the request filter calls this when a request ends. */
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

    /** Wraps a task so it runs under the context captured now (at wrap time), restoring the executing thread previous context afterwards. Use it when handing work to an executor. */
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
