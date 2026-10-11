// @find: chat queue runner, start next queued message, drain queue, scheduled sweep every twenty seconds, ChatQueueRunner, conversation work ended, restart recovery
// @what: Starts a conversation's next waiting message once its work has ended, on demand and by a periodic sweep.
// @flow: Called after tasks end and messages route; calls CoordinatorService.startNextQueued.
package os.aiworkforce.orchestrator.chat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.context.RequestContext;

/**
 * Starts a conversation's next waiting message once its work has ended.
 *
 * <p>Asked to look whenever work in a conversation ends (a task finishes, a goal is stopped) and
 * after every message is routed, and every twenty seconds for anything those missed - a restart,
 * say. Each look runs off the caller's thread, so the work that just ended is never held up by
 * the next one starting, and the claim itself is made under the conversation lock, so two looks
 * at once start one message, not two.
 */
@Component
public class ChatQueueRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatQueueRunner.class);

    /** Resolved lazily, because the coordinator depends on this class; avoids a start-up cycle. */
    private final ObjectProvider<CoordinatorService> coordinator;
    /** The queue store, used by the sweep to tidy and to find conversations with waiting messages. */
    private final ChatQueue queue;
    /** Conversations a look is already running for; another request for one of them is folded into it. */
    private final java.util.Set<UUID> looking = ConcurrentHashMap.newKeySet();
    /**
     * Conversations asked to be looked at again while a look was running, so a late request is never lost.
     */
    private final java.util.Set<UUID> lookAgain = ConcurrentHashMap.newKeySet();
    /** Where looks run; a virtual thread per look unless a test replaces it. */
    private Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Creates the runner over the coordinator, resolved on demand, and the queue store. */
    public ChatQueueRunner(ObjectProvider<CoordinatorService> coordinator, ChatQueue queue) {
        this.coordinator = coordinator;
        this.queue = queue;
    }

    /** Runs looks on the caller's thread instead, for a test. */
    void useExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Looks at a conversation soon, off this thread.
     *
     * <p>Called by {@code ChatGoalListener} after work ends, after a message is routed, and by
     * {@link #sweep}. At most one look runs per conversation: a request that arrives during a look
     * marks the conversation to be looked at again and returns at once. Each look calls {@link
     * #drainNow}. Missing ids are ignored; no exception reaches the caller.
     */
    // @find: drain queue soon, start next queued message when work ends
    public void drainSoon(UUID orgId, UUID conversationId) {
        if (orgId == null || conversationId == null) {
            return;
        }
        if (!looking.add(conversationId)) {
            lookAgain.add(conversationId);
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    lookAgain.remove(conversationId);
                    drainNow(orgId, conversationId);
                } while (lookAgain.contains(conversationId));
            } finally {
                looking.remove(conversationId);
            }
        });
    }

    /**
     * Starts the next waiting message if the conversation is idle. Never throws.
     *
     * <p>Clears the thread's request context for the duration, because a look must not run with
     * whatever identity the thread last held, and restores it afterwards. Calls {@code
     * CoordinatorService.startNextQueued}, which claims the message under the conversation lock and
     * starts its work. A failure is logged as a warning; the sweep tries again later.
     */
    void drainNow(UUID orgId, UUID conversationId) {
        RequestContext.Snapshot before = RequestContext.snapshot();
        try {
            // A look is never made with whatever request context the thread happened to inherit.
            RequestContext.clear();
            CoordinatorService service = coordinator.getIfAvailable();
            if (service != null && service.startNextQueued(orgId, conversationId)) {
                log.info("Started the next queued message in conversation {}", conversationId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not start a queued message in conversation {}: {}", conversationId, e.toString());
        } finally {
            RequestContext.restore(before);
        }
    }

    // @find: scheduled sweep, start missed queued messages, every twenty seconds
    @Scheduled(
            fixedDelayString = "${aiwos.chat.queue-sweep-interval:PT20S}",
            initialDelayString = "${aiwos.chat.queue-sweep-initial-delay:PT30S}")
    /**
     * Periodic safety net, every twenty seconds by default after a thirty second start-up delay.
     *
     * <p>Tidies the queue, then asks for a look at every conversation with a waiting message, which
     * recovers anything missed by the on-demand path, such as after a restart. Configured by
     * {@code aiwos.chat.queue-sweep-interval} and {@code aiwos.chat.queue-sweep-initial-delay}. A
     * failure is logged and the next sweep runs as normal.
     */
    public void sweep() {
        try {
            queue.tidy();
            for (UUID[] waiting : queue.waitingConversations()) {
                drainSoon(waiting[0], waiting[1]);
            }
        } catch (RuntimeException e) {
            log.warn("The chat queue sweep failed: {}", e.toString());
        }
    }
}
