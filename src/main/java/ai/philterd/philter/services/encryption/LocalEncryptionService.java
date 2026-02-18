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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class LocalEncryptionService extends EncryptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalEncryptionService.class);

    public LocalEncryptionService() {
        super(new LocalKeyProvider());
    }

    @Override
    public String generateEncryptionKey() {

        try {

            final KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);

            final SecretKey secretKey = keyGen.generateKey();
            final byte[] keyBytes = secretKey.getEncoded();

            return Base64.getEncoder().encodeToString(keyBytes);

        } catch (Exception ex) {
            throw new RuntimeException("Unable to generate encryption key.", ex);
        }

    }

    @Override
    public EncryptResult encrypt(final String data, final String userId) {

        final KeyResponse keyResponse = keyProvider.getKey(userId);

        final byte[] encryptionKey = EncryptionService.base64Decode(keyResponse.getPlainKey());

        // Validate the key length. For AES-256, the key must be 32 bytes.
        if(encryptionKey.length != KEY_LENGTH_BITS / 8) {
            throw new IllegalArgumentException("Invalid key length. Must be 32 bytes for AES-256.");
        }

        try {

            final SecretKeySpec secretKey = new SecretKeySpec(encryptionKey, "AES");
            final IvParameterSpec iv = generateIv();
            final Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

            final byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

            final byte[] combined = new byte[iv.getIV().length + encryptedBytes.length];
            System.arraycopy(iv.getIV(), 0, combined, 0, iv.getIV().length);
            System.arraycopy(encryptedBytes, 0, combined, iv.getIV().length, encryptedBytes.length);
            final String encryptedText = Base64.getEncoder().encodeToString(combined);

            return new EncryptResult(encryptedText, keyResponse.getPlainKey());

        } catch (Exception ex) {
            LOGGER.error("Error encrypting data: {}", ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }

    }

    @Override
    public String decrypt(final String encryptedText, final String encryptionKey) {

        final byte[] key = EncryptionService.base64Decode(encryptionKey);

        // Validate the key length. For AES-256, the key must be 32 bytes.
        if(key.length != KEY_LENGTH_BITS / 8) {
            //if (decryptionKey.getBytes(StandardCharsets.UTF_8).length != KEY_LENGTH_BITS / 8) {
            throw new IllegalArgumentException("Invalid key length. Must be 32 bytes for AES-256.");
        }

        final SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        final byte[] combined = Base64.getDecoder().decode(encryptedText);

        final byte[] ivBytes = new byte[16];
        System.arraycopy(combined, 0, ivBytes, 0, 16);
        IvParameterSpec iv = new IvParameterSpec(ivBytes);

        final byte[] encryptedBytes = new byte[combined.length - 16];
        System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.length);

        try {

            final Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");

            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

            final byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (Exception ex) {
            LOGGER.error("Error decrypting data: {}", ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }

    }

    /**
     * Generates a new, cryptographically secure Initialization Vector (IV).
     * The IV is 16 bytes for AES.
     * @return a new IvParameterSpec instance.
     */
    private IvParameterSpec generateIv() {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        return new IvParameterSpec(iv);
    }

}