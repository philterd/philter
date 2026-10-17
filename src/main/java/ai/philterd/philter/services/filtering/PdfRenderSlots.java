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
package ai.philterd.philter.services.filtering;

import ai.philterd.philter.utils.EnvUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * How many PDFs this instance redacts at once, from {@code PDF_MAX_CONCURRENT} (default 2; zero or less sets no limit).
 * Each page being redacted is rendered to an image in memory, so PDFs redacting at the same time multiply what one
 * needs. Shared by synchronous requests and the queue worker.
 */
public final class PdfRenderSlots {

    public static final int DEFAULT_MAX_CONCURRENT = 2;

    private static volatile Semaphore slots = create(EnvUtils.getInt("PDF_MAX_CONCURRENT", DEFAULT_MAX_CONCURRENT));

    private PdfRenderSlots() {
    }

    private static Semaphore create(final int permits) {
        return permits > 0 ? new Semaphore(permits, true) : null;
    }

    /** A held slot, released by closing it. */
    public interface Slot extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Takes a slot if one is free and no one is already waiting for one, or returns {@code null} at once if not. The
     * timed form keeps the semaphore's fairness, which {@code tryAcquire()} ignores, so requests cannot keep taking
     * freed slots ahead of a waiting queue worker.
     */
    public static Slot tryAcquire() {
        final Semaphore current = slots;
        if (current == null) {
            return () -> { };
        }
        try {
            return current.tryAcquire(0, TimeUnit.SECONDS) ? current::release : null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Takes a slot, waiting for one to be released. */
    public static Slot acquire() throws InterruptedException {
        final Semaphore current = slots;
        if (current == null) {
            return () -> { };
        }
        current.acquire();
        return current::release;
    }

    /** Test hook: replaces the slots with {@code permits} free ones, or no limit when zero or less. */
    public static void setForTesting(final int permits) {
        slots = create(permits);
    }

}
