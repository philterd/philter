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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRenderSlotsTest {

    @AfterEach
    void restore() {
        PdfRenderSlots.setForTesting(PdfRenderSlots.DEFAULT_MAX_CONCURRENT);
    }

    @Test
    void aRequestIsRefusedWhileEverySlotIsHeldAndTheWorkerWaits() throws Exception {
        PdfRenderSlots.setForTesting(1);

        final PdfRenderSlots.Slot held = PdfRenderSlots.tryAcquire();
        assertNotNull(held);
        assertNull(PdfRenderSlots.tryAcquire(), "no slot is free");

        final CompletableFuture<PdfRenderSlots.Slot> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return PdfRenderSlots.acquire();
            } catch (final InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(waiting.isDone(), "the worker waits for a slot");

        held.close();
        waiting.get(5, TimeUnit.SECONDS).close();
        assertNotNull(PdfRenderSlots.tryAcquire(), "a released slot is free again");
    }

    @Test
    void aRequestDoesNotTakeAFreedSlotAheadOfAWaitingWorker() throws Exception {
        PdfRenderSlots.setForTesting(1);

        final PdfRenderSlots.Slot held = PdfRenderSlots.tryAcquire();
        final CompletableFuture<PdfRenderSlots.Slot> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return PdfRenderSlots.acquire();
            } catch (final InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);

        held.close();
        // Right after the release, while the worker is still queued or has just taken it: either way the slot is the
        // worker's, so the request finds none. tryAcquire() without a timeout would usually take it first.
        assertNull(PdfRenderSlots.tryAcquire());
        waiting.get(5, TimeUnit.SECONDS).close();
    }

    @Test
    void zeroSetsNoLimit() {
        PdfRenderSlots.setForTesting(0);
        for (int i = 0; i < 50; i++) {
            assertTrue(PdfRenderSlots.tryAcquire() != null);
        }
    }

}
