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
package ai.philterd.philter.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdGeneratorTest {

    @Test
    void generatesParseableUuid() {
        final String id = RequestIdGenerator.generate();
        // Must round-trip as a canonical UUID string.
        assertEquals(id, UUID.fromString(id).toString());
    }

    @Test
    void generatesVersion7Uuids() {
        final UUID uuid = UUID.fromString(RequestIdGenerator.generate());
        assertEquals(7, uuid.version());
        // RFC 4122 variant.
        assertEquals(2, uuid.variant());
    }

    @Test
    void generatesUniqueValues() {
        final String first = RequestIdGenerator.generate();
        final String second = RequestIdGenerator.generate();
        assertNotEquals(first, second);
    }

    @Test
    void idsAreTimeOrdered() throws InterruptedException {
        final String earlier = RequestIdGenerator.generate();
        Thread.sleep(2);
        final String later = RequestIdGenerator.generate();
        // Version 7 UUIDs embed a millisecond timestamp in their high bits, so a lexicographic
        // comparison reflects creation order.
        assertTrue(earlier.compareTo(later) < 0,
                "Expected " + earlier + " to sort before " + later);
    }

}
