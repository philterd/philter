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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * The rules for sign-in challenges, the same in every storage implementation: how a token is made, how long it
 * lasts, and that only its hash is stored. An implementation stores the hashes and uses each up once.
 */
public abstract class StoredSignInChallengeDataService implements SignInChallengeDataService {

    /** How long a challenge can be used. */
    public static final int LIFETIME_SECONDS = 300;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Stores a challenge by its token's hash. */
    protected abstract void insert(final String tokenHash, final String userId, final Date expiresAt);

    /**
     * Removes the challenge with this token hash if it expires after {@code now}, and returns its user's id, or
     * null when there is none. Of two concurrent calls for one challenge, at most one returns the user.
     */
    protected abstract String remove(final String tokenHash, final Date now);

    @Override
    public Challenge create(final String userId) {
        final byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        final Date expiresAt = new Date(System.currentTimeMillis() + LIFETIME_SECONDS * 1000L);
        insert(EncryptionService.hashSha256(token), userId, expiresAt);
        return new Challenge(token, expiresAt);
    }

    @Override
    public String consume(final String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return remove(EncryptionService.hashSha256(token), new Date());
    }

}
