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

import ai.philterd.philter.data.entities.LedgerEntity;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;

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

            // No wrapping in this double, so the stored key is already the data key.
            @Override
            public String decryptKey(final String storedKey) {
                return storedKey;
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
    @DisplayName("A value too short to hold an IV is refused with a message that says so")
    void tooShortToHoldAnIvIsRejectedClearly() {

        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("data", "user-1");

        // Anything at or under the 16-byte IV cannot be a ciphertext. Sliced blindly this failed
        // inside arraycopy, with an index message that said nothing about what was wrong.
        for (final int length : new int[]{0, 1, 15, 16}) {

            final String truncated = Base64.getEncoder().encodeToString(new byte[length]);

            final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.decrypt(truncated, result.getEncryptionKey()),
                    length + " bytes is not a valid encrypted value");

            assertTrue(thrown.getMessage().contains("too short"),
                    "the message must name the problem; was: " + thrown.getMessage());

        }

        // One byte past the IV is structurally valid, so it fails as a decryption rather than here.
        final String justLongEnough = Base64.getEncoder().encodeToString(new byte[17]);
        assertThrows(RuntimeException.class, () -> service.decrypt(justLongEnough, result.getEncryptionKey()));

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
    void realProviderRoundTripsThroughTheWrappedKey() {
        final LocalEncryptionService service =
                new LocalEncryptionService(new LocalKeyProvider(Base64.getEncoder().encodeToString(new byte[32])));

        final String plaintext = "SSN 123-45-6789";
        final EncryptResult result = service.encrypt(plaintext, "user-1");

        assertEquals(plaintext, service.decrypt(result.getEncryptedText(), result.getEncryptionKey()));
    }

    @Test
    void whatIsPersistedCannotDecryptTheRecordWithoutTheMasterKey() {
        final LocalEncryptionService service =
                new LocalEncryptionService(new LocalKeyProvider(Base64.getEncoder().encodeToString(new byte[32])));

        final EncryptResult result = service.encrypt("SSN 123-45-6789", "user-1");

        // These two values are exactly what a record holds, and are all an attacker with read access to
        // the database has. Neither the stored key nor the ciphertext is usable without the master key.
        assertThrows(RuntimeException.class,
                () -> serviceWithKey(OTHER_KEY).decrypt(result.getEncryptedText(), result.getEncryptionKey()));
        assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode(result.getEncryptionKey()));
    }

    @Test
    void persistedDocumentHoldsNothingThatDecryptsItWithoutTheMasterKey() throws Exception {
        final String master = Base64.getEncoder().encodeToString(new byte[32]);
        final LocalEncryptionService service = new LocalEncryptionService(new LocalKeyProvider(master));

        // The document written to MongoDB, produced the same way the data layer produces it.
        final LedgerEntity entity = new LedgerEntity(new ObjectId(), "doc-1", "123-45-6789",
                "{{{REDACTED-ssn}}}", 0L, "dochash", "prevhash", "f.txt", "ssn", "policy", 1, "confighash");
        final Document document = entity.toDocument(service);

        // No field of the persisted document contains the master key.
        for (final String field : document.keySet()) {
            final Object value = document.get(field);
            if (value instanceof String s) {
                assertFalse(s.contains(master), "field " + field + " leaks the master key");
            }
        }

        // The stored token is real ciphertext, and the key stored beside it cannot open it on its own.
        assertNotEquals("123-45-6789", document.getString("token"));
        assertThrows(RuntimeException.class, () -> serviceWithKey(OTHER_KEY)
                .decrypt(document.getString("token"), document.getString("token_encrypted_key")));

        // The master key does open it.
        assertEquals("123-45-6789",
                service.decrypt(document.getString("token"), document.getString("token_encrypted_key")));
    }

    @Test
    void encryptResultDoesNotExposeKeyMaterial() {
        final LocalEncryptionService service = service();
        final EncryptResult result = service.encrypt("data", "user-1");

        // toString reaches logs and exception messages.
        assertFalse(result.toString().contains(result.getEncryptionKey()));
    }

    @Test
    void generatedKeyIsValidAes256() {
        final String key = serviceWithKey(OTHER_KEY).generateEncryptionKey();
        assertTrue(Base64.getDecoder().decode(key).length == 32);
    }


    // ----- The byte path. This is what carries the documents themselves: an uploaded PDF is stored
    // through encryptBytes and read back through decryptBytes, so it holds the PII in its original
    // form rather than a field of it.

    @Test
    @DisplayName("Bytes round-trip unchanged, including the shapes a PDF actually takes")
    void bytesRoundTrip() {

        final LocalEncryptionService service = service();

        final byte[][] payloads = {
                new byte[0],
                new byte[]{0x25, 0x50, 0x44, 0x46},                 // %PDF
                new byte[]{0, 0, 0, 0},                             // all zeroes
                new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x00},  // high bytes and a NUL
                "text that happens to be bytes".getBytes(StandardCharsets.UTF_8),
        };

        for (final byte[] payload : payloads) {
            final EncryptedBytes encrypted = service.encryptBytes(payload, "user-1");
            assertArrayEquals(payload, service.decryptBytes(encrypted.ciphertext(), encrypted.encryptionKey()),
                    "a " + payload.length + "-byte payload must come back exactly");
        }

    }

    @Test
    @DisplayName("A large payload round-trips, so nothing is truncated at a block boundary")
    void aLargePayloadRoundTrips() {

        final LocalEncryptionService service = service();

        final byte[] large = new byte[1024 * 512];
        new java.util.Random(42).nextBytes(large);

        final EncryptedBytes encrypted = service.encryptBytes(large, "user-1");

        assertArrayEquals(large, service.decryptBytes(encrypted.ciphertext(), encrypted.encryptionKey()));

    }

    @Test
    @DisplayName("The same bytes encrypt differently every time")
    void byteEncryptionIsNonDeterministic() {

        final LocalEncryptionService service = service();
        final byte[] payload = "the same document twice".getBytes(StandardCharsets.UTF_8);

        final byte[] first = service.encryptBytes(payload, "user-1").ciphertext();
        final byte[] second = service.encryptBytes(payload, "user-1").ciphertext();

        assertFalse(java.util.Arrays.equals(first, second),
                "a repeated IV would leak that two stored documents are identical");

        // The difference is in the IV, which is the leading block.
        assertFalse(java.util.Arrays.equals(
                        java.util.Arrays.copyOf(first, 16), java.util.Arrays.copyOf(second, 16)),
                "the IV must be fresh for each encryption");

    }

    @Test
    @DisplayName("What is stored is the IV, then the ciphertext, then the tag")
    void theStoredLayoutIsIvThenCiphertextThenTag() {

        final LocalEncryptionService service = service();
        final byte[] payload = new byte[100];

        final byte[] stored = service.encryptBytes(payload, "user-1").ciphertext();

        // 16 bytes of IV and a 128-bit GCM tag around the ciphertext. A shorter tag would still
        // decrypt and would still be called authenticated, so the length is worth pinning.
        assertEquals(16 + payload.length + 16, stored.length);

    }

    @Test
    @DisplayName("A tampered document is refused wherever the change was made")
    void tamperedBytesAreRejected() {

        final LocalEncryptionService service = service();
        final EncryptedBytes encrypted = service.encryptBytes(new byte[64], "user-1");

        // Every region: the IV, the ciphertext, and the tag. Unauthenticated modes only notice some
        // of these, and then only sometimes.
        for (final int position : new int[]{0, 15, 16, 40, encrypted.ciphertext().length - 1}) {
            final byte[] tampered = encrypted.ciphertext().clone();
            tampered[position] ^= 0x01;
            assertThrows(RuntimeException.class,
                    () -> service.decryptBytes(tampered, encrypted.encryptionKey()),
                    "a byte flipped at offset " + position + " must be detected");
        }

    }

    @Test
    @DisplayName("A truncated document is refused")
    void truncatedBytesAreRejected() {

        final LocalEncryptionService service = service();
        final EncryptedBytes encrypted = service.encryptBytes(new byte[64], "user-1");

        final byte[] truncated = java.util.Arrays.copyOf(
                encrypted.ciphertext(), encrypted.ciphertext().length - 1);

        assertThrows(RuntimeException.class, () -> service.decryptBytes(truncated, encrypted.encryptionKey()));

    }

    @Test
    @DisplayName("Another key cannot read a document")
    void bytesDoNotDecryptWithAnotherKey() {

        final LocalEncryptionService service = service();
        final EncryptedBytes encrypted = service.encryptBytes("secret document".getBytes(StandardCharsets.UTF_8), "user-1");

        assertThrows(RuntimeException.class,
                () -> service.decryptBytes(encrypted.ciphertext(), OTHER_KEY));

    }

    @Test
    @DisplayName("A key of the wrong length is refused on the byte path too")
    void byteEncryptionRejectsWrongLengthKey() {

        final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        final LocalEncryptionService service = serviceWithKey(shortKey);

        assertThrows(IllegalArgumentException.class, () -> service.encryptBytes(new byte[8], "user-1"));

        final LocalEncryptionService valid = service();
        final EncryptedBytes encrypted = valid.encryptBytes(new byte[8], "user-1");
        assertThrows(IllegalArgumentException.class, () -> valid.decryptBytes(encrypted.ciphertext(), shortKey));

    }

    @Test
    @DisplayName("A document and a string are encrypted under the same wrapped key")
    void bothPathsCarryTheWrappedKeyThatOpensThem() {

        final LocalEncryptionService service = new LocalEncryptionService(
                new LocalKeyProvider(Base64.getEncoder().encodeToString(new byte[32])));

        final EncryptedBytes bytes = service.encryptBytes("document".getBytes(StandardCharsets.UTF_8), "user-1");
        final EncryptResult text = service.encrypt("field", "user-1");

        // Each record carries its own wrapped data key, so one record's key opens only that record.
        assertNotEquals(bytes.encryptionKey(), text.getEncryptionKey(),
                "each record must get its own data key");

        assertArrayEquals("document".getBytes(StandardCharsets.UTF_8),
                service.decryptBytes(bytes.ciphertext(), bytes.encryptionKey()));
        assertEquals("field", service.decrypt(text.getEncryptedText(), text.getEncryptionKey()));

        assertThrows(RuntimeException.class, () -> service.decryptBytes(bytes.ciphertext(), text.getEncryptionKey()),
                "another record's key must not open this one");

    }

}
