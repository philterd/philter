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

import java.util.Base64;

public class LocalKeyProvider extends KeyProvider {

    /**
     * Name of the environment variable holding the base64-encoded 32-byte (AES-256)
     * encryption key used to encrypt sensitive data at rest.
     */
    public static final String ENCRYPTION_KEY_ENV = "PHILTER_ENCRYPTION_KEY";

    private static final int AES_256_KEY_LENGTH_BYTES = 32;

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

    @Override
    public KeyResponse getKey(String userId) {
        // The local key provider uses the same configured key for all users.
        return new KeyResponse(key, key);
    }

}
