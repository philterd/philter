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

import java.util.Date;

/**
 * Challenges issued at sign-in to a user enrolled in MFA: proof the password was right, exchanged with a
 * code for a session key. Each is short-lived and single-use, and only its hash is stored. Kept in the
 * database rather than in memory so any node can complete a sign-in another node began.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A challenge can be used once: of two attempts to use it concurrently, at most one succeeds.</li>
 *   <li>A challenge expires at its expiry time, after which it cannot be used, whether or not it has been
 *   removed yet.</li>
 * </ul>
 */
public interface SignInChallengeDataService {

    /** A challenge as issued: the token, returned once, and when it expires. */
    record Challenge(String token, Date expiresAt) {
    }

    Challenge create(final String userId);

    /**
     * Uses up a challenge, whether or not the code that comes with it is right, so each one allows a
     * single attempt.
     *
     * @return the user it was issued to, or {@code null} if it is unknown, used, or expired.
     */
    String consume(final String token);

}
