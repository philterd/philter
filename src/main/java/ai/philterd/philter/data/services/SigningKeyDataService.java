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

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * The key pairs Philter signs its output with: the active key, and the public keys of earlier ones so earlier
 * signatures can still be verified.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A key id is unique, and exactly one key is active; when several instances start at once, they agree
 *   on the same active key.</li>
 *   <li>Private keys are stored encrypted, and what is written is read back unchanged.</li>
 * </ul>
 */
public interface SigningKeyDataService {

    boolean isExternallyManaged();

    /**
     * Persist the candidate first, then atomically publish it. Failed publication leaves the
     * previous pointer intact; retained keys keep in-flight and historical signatures verifiable.
     *
     * @param actingUserId    The user who rotated the key. Always a user id, never an API key id, so a
     *                        reader of the audit log knows what the recorded principal is.
     * @param clientIpAddress Where the request came from, or null when unavailable.
     * @param details         How the rotation was requested, which is what names the API key when one
     *                        was used.
     * @return The id of the key that is now active.
     */
    String regenerate(final String requestId, final String actingUserId,
            final String clientIpAddress, final String details);

    /** The id of the key new signatures are made with. */
    String getActiveKeyId();

    /** The private key and the id that names it, from one read, so a caller cannot mix two keys. */
    SigningKey currentSigningKey();

    /** A signing key paired with the id an entry must record for it to be verifiable later. */
    record SigningKey(PrivateKey privateKey, String keyId) { }

    /**
     * Returns the public key with the given id, active or superseded, so a historical signature can
     * still be verified. Returns null when no key matches.
     */
    PublicKey findPublicKeyById(final String keyId);

    PublicKey getPublicKey();

    /** PEM for any retained key, active or superseded. Returns null when the id is unknown. */
    String getPublicKeyPem(final String keyId);

    /** Returns the public key in PEM (BEGIN PUBLIC KEY) format. */
    String getPublicKeyPem();

    record PublicKeyInfo(String keyId, String pem, String jwk, String fingerprint) { }

    /** All advertised fields come from one key selection even if rotation overlaps this request. */
    PublicKeyInfo getPublicKeyInfo();

}
