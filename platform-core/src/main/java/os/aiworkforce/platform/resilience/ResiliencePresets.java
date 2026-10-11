// @find: resilience, circuit breaker, timeout, retry, backoff, outbound calls, provider resilience, service call patience
// @what: Builds circuit breakers, timeouts and retry settings for outbound calls to models and sibling services.
// @flow: Used by LLM routing and service clients
package os.aiworkforce.platform.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Builds the circuit breakers, timeouts and backoff the platform uses on every outbound call.
 *
 * <p>A named dependency - {@code provider.openrouter}, {@code service.identity} - can override any
 * preset from configuration, because a model provider that is slow by nature needs a different
 * patience from a sibling service that should answer in milliseconds.
 */
@Component
public class ResiliencePresets {

    private final PlatformProperties.Resilience defaults;
    private final CircuitBreakerRegistry registry;

    /**
     * Takes the resilience defaults from the settings tree and creates an empty breaker registry.
     *
     * <p>The registry is private to this bean, so breaker state is shared by every caller in the
     * process and not across processes.
     */
    public ResiliencePresets(PlatformProperties properties) {
        this.defaults = properties.resilience();
        this.registry = CircuitBreakerRegistry.ofDefaults();
    }

    /**
     * The breaker for a named dependency, created on first use.
     *
     * <p>A breaker is per dependency, never global. One failing model provider must not stop calls
     * to the other six; a shared breaker would turn one vendor's outage into a platform outage.
     */
    public CircuitBreaker circuitBreaker(String dependency) {
        return registry.circuitBreaker(dependency, () -> circuitBreakerConfig(dependency));
    }

    /**
     * Builds one breaker's configuration, preferring the dependency's override and then the default.
     *
     * <p>Called by {@link #circuitBreaker(String)} once per dependency, at first use. Caller faults are
     * excluded from the failure rate; see {@link #isCallerFault(Throwable)}.
     */
    private CircuitBreakerConfig circuitBreakerConfig(String dependency) {
        PlatformProperties.DependencyOverride override = defaults.forDependency(dependency);
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(
                        override.failureRatePercentThreshold() != null
                                ? override.failureRatePercentThreshold()
                                : defaults.failureRatePercentThreshold())
                .slidingWindowSize(
                        override.slidingWindowSize() != null
                                ? override.slidingWindowSize()
                                : defaults.slidingWindowSize())
                // Below this many calls the breaker stays closed: a 100 per cent failure rate
                // over two calls is noise, and opening on it would be worse than the failure.
                .minimumNumberOfCalls(
                        override.minimumNumberOfCalls() != null
                                ? override.minimumNumberOfCalls()
                                : defaults.minimumNumberOfCalls())
                .waitDurationInOpenState(
                        override.waitDurationInOpenState() != null
                                ? override.waitDurationInOpenState()
                                : defaults.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(defaults.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // A 4xx means the request was wrong, not that the dependency is unwell. Counting
                // it would let a client's own bad input open a breaker for everybody else.
                .ignoreException(ResiliencePresets::isCallerFault)
                .build();
    }

    /**
     * Whether a failure was caused by the caller's request, and so says nothing about the dependency.
     *
     * <p>True for a deliberate 4xx {@link ApiException}, except a rate limit, which does indicate an
     * overloaded dependency. Any other exception, including timeouts, counts as a failure.
     *
     * @param throwable the exception raised by the protected call
     */
    public static boolean isCallerFault(Throwable throwable) {
        if (throwable instanceof ApiException api) {
            int status = api.status();
            return status >= 400 && status < 500 && api.code() != ErrorCode.RATE_LIMITED;
        }
        return false;
    }

    /**
     * The concurrency cap for a dependency, with a two second wait for a free slot.
     *
     * <p>Returns configuration only; the caller builds the bulkhead. The limit is the override, or the
     * default of 25 concurrent calls.
     */
    public BulkheadConfig bulkhead(String dependency) {
        PlatformProperties.DependencyOverride override = defaults.forDependency(dependency);
        int limit = override.bulkheadMaxConcurrentCalls() != null
                ? override.bulkheadMaxConcurrentCalls()
                : defaults.bulkheadMaxConcurrentCalls();
        return BulkheadConfig.custom()
                .maxConcurrentCalls(limit)
                .maxWaitDuration(Duration.ofSeconds(2))
                .build();
    }

    /**
     * The call timeout for a dependency, cancelling the running call when it expires.
     *
     * <p>Returns configuration only. Slow providers raise the timeout through an override.
     */
    public TimeLimiterConfig timeLimiter(String dependency) {
        PlatformProperties.DependencyOverride override = defaults.forDependency(dependency);
        return TimeLimiterConfig.custom()
                .timeoutDuration(
                        override.callTimeout() != null ? override.callTimeout() : defaults.defaultCallTimeout())
                .cancelRunningFuture(true)
                .build();
    }

    /**
     * The number of attempts, including the first, that a caller may make against a dependency.
     */
    public int maxAttempts(String dependency) {
        PlatformProperties.DependencyOverride override = defaults.forDependency(dependency);
        return override.maxRetryAttempts() != null ? override.maxRetryAttempts() : defaults.maxRetryAttempts();
    }

    /**
     * How long to wait before attempt {@code attempt}, counting from one.
     *
     * <p>Exponential, capped, and jittered. The jitter is not a refinement: without it, every
     * client that failed together retries together, and a dependency that is just coming back is
     * knocked over by the synchronised wave. Full jitter spreads the herd across the window.
     */
    public Duration backoff(String dependency, int attempt) {
        long initial = defaults.retryInitialBackoff().toMillis();
        long max = defaults.retryMaxBackoff().toMillis();
        double multiplier = Math.pow(defaults.retryBackoffMultiplier(), Math.max(0, attempt - 1));
        long ceiling = (long) Math.min(max, initial * multiplier);
        if (!defaults.retryJitter()) {
            return Duration.ofMillis(ceiling);
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, Math.max(1, ceiling) + 1));
    }

    /**
     * The underlying registry, for health reporting and tests.
     */
    public CircuitBreakerRegistry registry() {
        return registry;
    }
}
