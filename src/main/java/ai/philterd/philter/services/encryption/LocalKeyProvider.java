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
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalKeyProvider extends KeyProvider {

    /**
     * Name of the environment variable holding the base64-encoded 32-byte (AES-256)
     * encryption key used to encrypt sensitive data at rest.
     */
    public static final String ENCRYPTION_KEY_ENV = "PHILTER_ENCRYPTION_KEY";

    private static final int AES_256_KEY_LENGTH_BYTES = 32;

    /** Marks a stored key as wrapped. Values without it are pre-release records holding the key itself. */
    private static final String WRAPPED_PREFIX = "v2:";

    private static final String WRAP_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalKeyProvider.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final AtomicBoolean LEGACY_KEY_WARNED = new AtomicBoolean(false);

    private final String key;

    public LocalKeyProvider() {
        this(System.getenv(ENCRYPTION_KEY_ENV));
    }

    LocalKeyProvider(final String configuredKey) {

        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException(ENCRYPTION_KEY_ENV + " is required but was not set. "
                    + "Generate one with: openssl rand -base64 32");
        }

        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey.trim());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException(ENCRYPTION_KEY_ENV + " must be a base64-encoded 32-byte (AES-256) key.", ex);
        }

        if (decoded.length != AES_256_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(ENCRYPTION_KEY_ENV + " must be a base64-encoded 32-byte (AES-256) key, "
                    + "but the configured value decodes to " + decoded.length + " bytes.");
        }

        this.key = configuredKey.trim();

    }

    /**
     * Returns a fresh random data key for this record, plus that data key wrapped under the master key
     * from {@code PHILTER_ENCRYPTION_KEY}. Only the wrapped form is ever persisted, so the master key
     * is never written next to the data it protects.
     */
    @Override
    public KeyResponse getKey(String userId) {

        final byte[] dataKey = new byte[AES_256_KEY_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(dataKey);

        return new KeyResponse(Base64.getEncoder().encodeToString(dataKey), wrap(dataKey));

    }

    @Override
    public String decryptKey(final String storedKey) {

        if (storedKey == null || !storedKey.startsWith(WRAPPED_PREFIX)) {
            // Pre-4.0.0-release records stored the key itself in this field. Read them so an existing
            // development database keeps working; nothing writes this form any more.
            warnOnceAboutLegacyKey();
            return storedKey;
        }

        return Base64.getEncoder().encodeToString(unwrap(storedKey.substring(WRAPPED_PREFIX.length())));

    }

    private String wrap(final byte[] dataKey) {

        try {

            final byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(nonce);

            final Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

            final byte[] wrapped = cipher.doFinal(dataKey);

            final byte[] combined = new byte[nonce.length + wrapped.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(wrapped, 0, combined, nonce.length, wrapped.length);

            return WRAPPED_PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to wrap the data key with " + ENCRYPTION_KEY_ENV + ".", ex);
        }

    }

    private byte[] unwrap(final String wrappedBase64) {

        try {

            final byte[] combined = Base64.getDecoder().decode(wrappedBase64);

            final byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
            System.arraycopy(combined, 0, nonce, 0, nonce.length);

            final byte[] wrapped = new byte[combined.length - nonce.length];
            System.arraycopy(combined, nonce.length, wrapped, 0, wrapped.length);

            final Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

            return cipher.doFinal(wrapped);

        } catch (final Exception ex) {
            // Authenticated decryption, so this means the wrong master key, not corrupt padding.
            throw new IllegalStateException("Unable to unwrap a data key. The configured "
                    + ENCRYPTION_KEY_ENV + " does not match the one this record was written with.", ex);
        }

    }

    private SecretKeySpec masterKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(key), "AES");
    }

    private static void warnOnceAboutLegacyKey() {
        if (LEGACY_KEY_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Read a record whose key was stored in the pre-release format (the key beside the "
                    + "ciphertext). Such records are readable but were never protected by {}. Re-encrypt or "
                    + "discard them; new records store a wrapped key.", ENCRYPTION_KEY_ENV);
        }
    }

}
