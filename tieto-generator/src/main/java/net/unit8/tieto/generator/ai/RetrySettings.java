package net.unit8.tieto.generator.ai;

import java.time.Duration;

/**
 * Timeout and retry policy for the HTTP AI providers.
 *
 * @param requestTimeout per-request timeout (a hung connection fails rather than
 *                       blocking the run forever)
 * @param maxRetries     number of retries after the first attempt on a transient
 *                       failure (429, 5xx, network error); 0 disables retrying
 * @param baseBackoff    base delay for exponential backoff ({@code baseBackoff * 2^attempt}),
 *                       used when the response carries no {@code Retry-After}
 */
record RetrySettings(Duration requestTimeout, int maxRetries, Duration baseBackoff) {

    static RetrySettings defaults() {
        return new RetrySettings(Duration.ofSeconds(120), 2, Duration.ofSeconds(1));
    }
}
