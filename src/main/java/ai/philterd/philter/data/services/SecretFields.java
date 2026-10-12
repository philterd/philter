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

import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptedBytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How secret fields are encrypted at rest, the same in every storage implementation: each is stored as its
 * ciphertext and the encrypted data key that opens it. An implementation stores the two values and gives them
 * back; it does not encrypt or decrypt anything itself.
 */
public final class SecretFields {

    private SecretFields() {
    }

    /** A sealed text value: the ciphertext and the encrypted data key that opens it. */
    public record Sealed(String ciphertext, String key) {
    }

    /** A sealed binary value: the ciphertext and the encrypted data key that opens it. */
    public record SealedBytes(byte[] ciphertext, String key) {
    }

    /** Seals a value for an owner, or returns null for a null value. */
    public static Sealed seal(final EncryptionService encryption, final String value, final String owner) {
        if (value == null) {
            return null;
        }
        final EncryptResult result = encryption.encrypt(value, owner);
        return new Sealed(result.getEncryptedText(), result.getEncryptionKey());
    }

    /**
     * Opens a sealed value, or returns null when there is none.
     *
     * @throws IllegalStateException when a value is stored without its key, which would mean it was stored in the
     *                               clear.
     */
    public static String open(final EncryptionService encryption, final String ciphertext, final String key) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        return encryption.decrypt(ciphertext, requireKey(key));
    }

    private static String requireKey(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("A secret field is stored without its encryption key.");
        }
        return key;
    }

    /** Seals bytes for an owner, or returns null for null bytes. */
    public static SealedBytes sealBytes(final EncryptionService encryption, final byte[] value, final String owner) {
        if (value == null) {
            return null;
        }
        final EncryptedBytes result = encryption.encryptBytes(value, owner);
        return new SealedBytes(result.ciphertext(), result.encryptionKey());
    }

    /**
     * Opens sealed bytes, or returns null when there are none.
     *
     * @throws IllegalStateException when bytes are stored without their key.
     */
    public static byte[] openBytes(final EncryptionService encryption, final byte[] ciphertext, final String key) {
        if (ciphertext == null) {
            return null;
        }
        return encryption.decryptBytes(ciphertext, requireKey(key));
    }

    /**
     * Seals a list of terms as one value, one term per line. An empty or absent list is stored as two empty
     * strings.
     */
    public static Sealed sealLines(final EncryptionService encryption, final List<String> lines, final String owner) {
        if (lines == null || lines.isEmpty()) {
            return new Sealed("", "");
        }
        return seal(encryption, String.join("\n", lines), owner);
    }

    /** Opens a list sealed by {@link #sealLines}. */
    public static List<String> openLines(final EncryptionService encryption, final String ciphertext, final String key) {
        final List<String> lines = new ArrayList<>();
        if (ciphertext == null || ciphertext.isEmpty()) {
            return lines;
        }
        final String joined = encryption.decrypt(ciphertext, requireKey(key));
        if (joined != null && !joined.isEmpty()) {
            lines.addAll(Arrays.asList(joined.split("\n")));
        }
        return lines;
    }

}
