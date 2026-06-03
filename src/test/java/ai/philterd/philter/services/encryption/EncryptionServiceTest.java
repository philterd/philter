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

import com.privacylogistics.FF3Cipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the static FPE key/tweak helpers on {@link EncryptionService} that back the
 * {@code FPE_ENCRYPT_REPLACE} strategy. The most important property is that the generated key and the
 * derived tweak are valid FF3-1 inputs — FF3 requires a hex key of 128/192/256 bits and a hex tweak of
 * 56 or 64 bits — so a round-trip through {@link FF3Cipher} is exercised directly.
 */
class EncryptionServiceTest {

    @Test
    void generateFpeKeyIsA256BitHexKey() {
        final String key = EncryptionService.generateFpeKey();
        assertTrue(key.matches("[0-9a-f]{64}"), "an FPE key must be 64 lowercase hex characters (256 bits)");
    }

    @Test
    void generateFpeKeyIsRandomEachCall() {
        assertNotEquals(EncryptionService.generateFpeKey(), EncryptionService.generateFpeKey());
    }

    @Test
    void deriveFpeTweakIsDeterministicHexOfTheRequiredLength() {
        final String key = EncryptionService.generateFpeKey();

        final String tweak = EncryptionService.deriveFpeTweak(key);
        assertTrue(tweak.matches("[0-9a-f]{16}"), "the tweak must be 16 hex characters (a 64-bit FF3 tweak)");

        // Stable for the same key (so FPE is deterministic), different for different keys.
        assertEquals(tweak, EncryptionService.deriveFpeTweak(key));
        assertNotEquals(tweak, EncryptionService.deriveFpeTweak(EncryptionService.generateFpeKey()));
    }

    @Test
    void generatedKeyAndTweakAreValidFf3InputsThatRoundTrip() throws Exception {
        final String key = EncryptionService.generateFpeKey();
        final String tweak = EncryptionService.deriveFpeTweak(key);

        // Constructing the cipher would throw if the key/tweak lengths were wrong; encrypt/decrypt must
        // also round-trip a numeric value (the FF3 default radix is 10).
        final FF3Cipher cipher = new FF3Cipher(key, tweak);
        final String ciphertext = cipher.encrypt("123456789");

        assertNotEquals("123456789", ciphertext, "encryption must change the value");
        assertEquals("123456789", cipher.decrypt(ciphertext), "decryption must recover the original");
    }

}
