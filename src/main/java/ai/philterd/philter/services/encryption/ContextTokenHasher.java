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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes a context's original token into the value stored and looked up in its place.
 *
 * <p>Keyed, because the values are the PII Philter detects and most of it is low-entropy: an SSN is
 * 10^9 candidates and a date of birth far fewer, so a bare hash of one is recoverable by enumeration
 * and identical for every user and deployment. An HMAC under a key held outside the database is not.
 *
 * <p>Deterministic, so redaction still finds a token by exact match on the stored value.
 */
public final class ContextTokenHasher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextTokenHasher.class);

    private static final String ALGORITHM = "HmacSHA256";

    /** Separates this key from the master's other use, wrapping data keys. */
    private static final String LABEL = "philter:context-token-hash";

    private static final byte[] KEY = deriveKey();

    private ContextTokenHasher() {
    }

    public static String hash(final String token) {
        return Hex.encodeHexString(hmac(KEY, token.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] deriveKey() {

        final String configured = System.getenv(LocalKeyProvider.ENCRYPTION_KEY_ENV);

        if (configured == null || configured.isBlank()) {
            // Philter will not start without the key, so this is reachable only outside a running
            // application. A per-JVM key keeps such a caller self-consistent; falling back to an
            // unkeyed hash would quietly restore what this class exists to prevent.
            LOGGER.warn("{} is not set, so context tokens are hashed under a key that lasts only as long "
                    + "as this JVM. Stored context entries will not be readable afterwards.",
                    LocalKeyProvider.ENCRYPTION_KEY_ENV);
            final byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            return ephemeral;
        }

        return hmac(Base64.getDecoder().decode(configured.trim()), LABEL.getBytes(StandardCharsets.UTF_8));

    }

    private static byte[] hmac(final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to hash a context token.", ex);
        }
    }

}
