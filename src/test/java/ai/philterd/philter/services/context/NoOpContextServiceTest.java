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
package ai.philterd.philter.services.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoOpContextServiceTest {

    @Test
    void withoutAContextEachDateGetsItsOwnShift() {
        // A request without a context keeps nothing, so each date is shifted by its own amount.
        final NoOpContextService service = new NoOpContextService();
        final AtomicInteger next = new AtomicInteger();
        assertEquals("1", service.computeValueIfAbsent("\0date-shift", "date", () -> String.valueOf(next.incrementAndGet())));
        assertEquals("2", service.computeValueIfAbsent("\0date-shift", "date", () -> String.valueOf(next.incrementAndGet())));
    }

}
