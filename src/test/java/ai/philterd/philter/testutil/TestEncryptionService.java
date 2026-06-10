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
package ai.philterd.philter.testutil;

import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.KeyProvider;
import ai.philterd.philter.services.encryption.KeyResponse;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A real, self-contained {@link EncryptionService} for tests: AES with a single generated key for all
 * users. It lets encrypted-service tests exercise real cryptography without depending on the
 * {@code PHILTER_ENCRYPTION_KEY} environment variable (the production key provider's only source). The
 * {@link EncryptionService} superclass constructor registers the BouncyCastle provider, so the
 * {@code "BC"} provider used below is available even in plain unit tests.
 */
public final class TestEncryptionService extends EncryptionService {

    private static final String ALG = "AES/GCM/NoPadding";

    public TestEncryptionService() {
        super(generatedKeyProvider());
    }

    private static KeyProvider generatedKeyProvider() {
        final String key = generateAes256Key();
        return new KeyProvider() {
            @Override
            public KeyResponse getKey(final String userId) {
                return new KeyResponse(key, key);
            }
        };
    }

    private static String generateAes256Key() {
        try {
            final KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            final SecretKey secretKey = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (final Exception ex) {
            throw new RuntimeException("Unable to generate encryption key.", ex);
        }
    }

    @Override
    public String generateEncryptionKey() {
        return generateAes256Key();
    }

    @Override
    public EncryptResult encrypt(final String data, final String userId) {
        final KeyResponse keyResponse = keyProvider.getKey(userId);
        final byte[] keyBytes = EncryptionService.base64Decode(keyResponse.getPlainKey());
        try {
            final SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            final byte[] ivBytes = new byte[16];
            new SecureRandom().nextBytes(ivBytes);
            final IvParameterSpec iv = new IvParameterSpec(ivBytes);
            final Cipher cipher = Cipher.getInstance(ALG, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);
            final byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            final byte[] combined = new byte[ivBytes.length + encrypted.length];
            System.arraycopy(ivBytes, 0, combined, 0, ivBytes.length);
            System.arraycopy(encrypted, 0, combined, ivBytes.length, encrypted.length);
            return new EncryptResult(Base64.getEncoder().encodeToString(combined), keyResponse.getPlainKey());
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public String decrypt(final String encryptedText, final String encryptionKey) {
        final byte[] keyBytes = EncryptionService.base64Decode(encryptionKey);
        final byte[] combined = Base64.getDecoder().decode(encryptedText);
        final byte[] ivBytes = new byte[16];
        System.arraycopy(combined, 0, ivBytes, 0, 16);
        final byte[] encrypted = new byte[combined.length - 16];
        System.arraycopy(combined, 16, encrypted, 0, encrypted.length);
        try {
            final SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            final Cipher cipher = Cipher.getInstance(ALG, "BC");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBytes));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

}
