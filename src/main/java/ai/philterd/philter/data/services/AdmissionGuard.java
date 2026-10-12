/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.data.services;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Serializes admission to the asynchronous queue across every instance sharing the storage, so its limits hold
 * when documents are submitted concurrently. A caller waits a short, bounded time for the guard, then is refused
 * with 503.
 *
 * <p>An admission that fails part way leaves the guard held, because a write still running in the storage could
 * otherwise race the next admission; an operator clears it once the storage is quiet.
 */
public interface AdmissionGuard {

    /** How long a caller waits for the guard. */
    long ACQUISITION_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * Takes the guard with this token and returns true, or returns false when another token holds it. A storage
     * failure is thrown: the guard may have been taken, so neither retrying nor releasing is safe.
     */
    boolean tryAcquire(final String token);

    /** Releases the guard if {@code token} holds it, and returns whether it did. */
    boolean release(final String token);

    /** Runs an admission while holding the guard. */
    default <T> T execute(final Supplier<T> action) {
        final String token = UUID.randomUUID().toString();
        final long deadline = System.nanoTime() + ACQUISITION_WAIT_NANOS;
        while (!tryAcquire(token)) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw busy();
            final long delay = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(
                    ThreadLocalRandom.current().nextLong(5, 26)));
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw busy();
            }
            if (deadline - System.nanoTime() <= 0) throw busy();
        }
        final T result;
        try {
            result = action.get();
        } catch (final QueueCapacityException rejected) {
            releaseOrFail(token);
            throw rejected;
        }
        // No finally: an ambiguous storage write must not race a subsequent admission.
        releaseOrFail(token);
        return result;
    }

    private void releaseOrFail(final String token) {
        if (!release(token)) {
            throw new IllegalStateException("Queue admission guard could not be released.");
        }
    }

    private static QueueCapacityException busy() {
        return new QueueCapacityException("Queue admission is busy or requires recovery. Retry later.", 503);
    }

}
