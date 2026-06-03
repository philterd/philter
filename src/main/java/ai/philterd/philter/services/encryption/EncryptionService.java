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

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

public abstract class EncryptionService {

    public abstract String generateEncryptionKey();
    public abstract EncryptResult encrypt(final String data, final String userId);
    public abstract String decrypt(final String encryptedText, final String encryptedDataKey);

    protected final int KEY_LENGTH_BITS = 256;
    protected final String ALGORITHM = "AES/GCM/NoPadding";
    protected final KeyProvider keyProvider;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public EncryptionService(final KeyProvider keyProvider) {
        Security.addProvider(new BouncyCastleProvider());
        this.keyProvider = keyProvider;
    }

    /**
     * Generates a key for FF3-1 format-preserving encryption (used by the {@code FPE_ENCRYPT_REPLACE}
     * strategy): 32 random bytes (AES-256) encoded as 64 lowercase hex characters. FF3 requires a
     * hex-encoded 128/192/256-bit key, so a hex string of this length is mandatory — note this is a
     * different encoding from {@link #generateEncryptionKey()}, which returns a Base64 AES key.
     */
    public static String generateFpeKey() {
        final byte[] keyBytes = new byte[32];
        SECURE_RANDOM.nextBytes(keyBytes);
        return Hex.encodeHexString(keyBytes);
    }

    /**
     * Derives the FF3-1 tweak for a user deterministically from their FPE key, so the same input always
     * encrypts to the same format-preserving output for that user (FPE provides referential integrity
     * by being deterministic). Returns 16 hex characters — an 8-byte / 64-bit FF3 tweak, which FF3
     * requires to be hex of 56 or 64 bits.
     */
    public static String deriveFpeTweak(final String fpeKey) {
        return hashSha256(fpeKey).substring(0, 16);
    }

    public static String hashSha256(final String data) {
        return DigestUtils.sha256Hex(data);
    }

    public static String hashSha256(final byte[] data) {
        return DigestUtils.sha256Hex(data);
    }

    public static String hashSha512(final String data) {
        return DigestUtils.sha512Hex(data);
    }

    public static byte[] base64Decode(final String data) {
        return Base64.getDecoder().decode(data);
    }

}
