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
package ai.philterd.philter.services.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContextTokenHasherTest {

    private static final String SSN = "123-45-6789";

    @Test
    @DisplayName("The same token always hashes the same, so lookups still resolve")
    void hashingIsDeterministic() {
        assertEquals(ContextTokenHasher.hash(SSN), ContextTokenHasher.hash(SSN));
    }

    @Test
    @DisplayName("The hash is keyed, not a bare digest of the token")
    void theHashIsKeyed() {
        // A bare SHA-256 of an SSN is 10^9 candidates: enumerable, and identical everywhere. If this
        // ever matches, the stored value has become guessable from the token alone again.
        assertNotEquals(EncryptionService.hashSha256(SSN), ContextTokenHasher.hash(SSN),
                "the stored hash must not be derivable from the token without the key");
    }

    @Test
    @DisplayName("Different tokens hash differently")
    void differentTokensHashDifferently() {
        assertNotEquals(ContextTokenHasher.hash(SSN), ContextTokenHasher.hash("987-65-4321"));
    }

    @Test
    @DisplayName("The hash is a SHA-256-width hex digest")
    void theHashIsHexOfTheExpectedWidth() {
        assertEquals(64, ContextTokenHasher.hash(SSN).length());
    }

}
