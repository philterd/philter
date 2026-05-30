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

import java.security.SecureRandom;
import java.util.UUID;

public class RequestIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a UUID version 7 (RFC 9562) as a string. Unlike a random version 4 UUID, a version 7
     * UUID embeds a Unix millisecond timestamp in its most significant bits, so generated ids sort
     * in (roughly) creation order, which is useful for correlating requests over time.
     */
    public static String generate() {
        return generateUuidV7().toString();
    }

    private static UUID generateUuidV7() {

        final long timestamp = System.currentTimeMillis();

        // 16 random bytes that we then overwrite with the timestamp, version, and variant bits.
        final byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);

        // 48-bit big-endian millisecond timestamp in the first 6 bytes.
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;

        // Version 7 in the high nibble of byte 6 (low nibble stays random).
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);

        // RFC 4122 variant (10xx) in the high bits of byte 8 (remaining bits stay random).
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        long mostSignificantBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificantBits = (mostSignificantBits << 8) | (bytes[i] & 0xFF);
        }

        long leastSignificantBits = 0;
        for (int i = 8; i < 16; i++) {
            leastSignificantBits = (leastSignificantBits << 8) | (bytes[i] & 0xFF);
        }

        return new UUID(mostSignificantBits, leastSignificantBits);

    }

}
