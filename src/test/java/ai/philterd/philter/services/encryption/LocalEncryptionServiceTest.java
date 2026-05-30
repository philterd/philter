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

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEncryptionServiceTest {

    /** A second, unrelated AES-256 key for the wrong-key test. */
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private LocalEncryptionService serviceWithKey(final String base64Key) {
        // The local key provider returns the same key for every user.
        return new LocalEncryptionService(new KeyProvider() {
            @Override
            public KeyResponse getKey(final String userId) {
                return new KeyResponse(base64Key, base64Key);
            }
        });
    }

    private LocalEncryptionService service() {
        final LocalEncryptionService keyGen = serviceWithKey(OTHER_KEY);
        // Use a freshly generated, valid AES-256 key for the round-trip tests.
        return serviceWithKey(keyGen.generateEncryptionKey());
    }

    @Test
    void encryptDecryptRoundTrip() {
        final LocalEncryptionService service = service();

        final String plaintext = "George Washington lives in 90210.";
        final EncryptResult result = service.encrypt(plaintext, "user-1");

        // Ciphertext is not the plaintext, and decrypting with the returned key recovers it.
        assertNotEquals(plaintext, result.getEncryptedText());
        assertEquals(plaintext, service.decrypt(result.getEncryptedText(), result.getEncryptionKey()));
    }

    @Test
    void encryptionIsNonDeterministic() {
        final LocalEncryptionService service = service();

        final String plaintext = "same input";
        final EncryptResult first = service.encrypt(plaintext, "user-1");
        final EncryptResult second = service.encrypt(plaintext, "user-1");

        // A random IV per call means identical plaintext yields different ciphertext...
        assertNotEquals(first.getEncryptedText(), second.getEncryptedText());
        // ...but both still decrypt back to the original.
        assertEquals(plaintext, service.decrypt(first.getEncryptedText(), first.getEncryptionKey()));
        assertEquals(plaintext, service.decrypt(second.getEncryptedText(), second.getEncryptionKey()));
    }

    @Test
    void emptyStringRoundTrips() {
        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("", "user-1");
        assertEquals("", service.decrypt(result.getEncryptedText(), result.getEncryptionKey()));
    }

    @Test
    void unicodeRoundTrips() {
        final LocalEncryptionService service = service();
        final String plaintext = "Zoë Müller, 東京, 🔒";
        final EncryptResult result = service.encrypt(plaintext, "user-1");
        assertEquals(plaintext, service.decrypt(result.getEncryptedText(), result.getEncryptionKey()));
    }

    @Test
    void decryptWithWrongKeyFails() {
        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("secret", "user-1");

        // GCM is authenticated: decrypting with a different key fails rather than returning garbage.
        assertThrows(RuntimeException.class, () -> service.decrypt(result.getEncryptedText(), OTHER_KEY));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("secret", "user-1");

        // Flip a byte in the trailing portion (past the IV) and confirm the GCM auth tag rejects it.
        final byte[] combined = Base64.getDecoder().decode(result.getEncryptedText());
        combined[combined.length - 1] ^= 0x01;
        final String tampered = Base64.getEncoder().encodeToString(combined);

        assertThrows(RuntimeException.class, () -> service.decrypt(tampered, result.getEncryptionKey()));
    }

    @Test
    void encryptRejectsWrongLengthKey() {
        // A 16-byte key is not valid for AES-256 and must be rejected up front.
        final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        final LocalEncryptionService service = serviceWithKey(shortKey);

        assertThrows(IllegalArgumentException.class, () -> service.encrypt("data", "user-1"));
    }

    @Test
    void decryptRejectsWrongLengthKey() {
        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("data", "user-1");

        final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> service.decrypt(result.getEncryptedText(), shortKey));
    }

    @Test
    void generatedKeyIsValidAes256() {
        final String key = serviceWithKey(OTHER_KEY).generateEncryptionKey();
        assertTrue(Base64.getDecoder().decode(key).length == 32);
    }

}
