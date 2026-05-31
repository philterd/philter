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
package ai.philterd.philter.services.cache;

import ai.philterd.philter.utils.EnvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks failed UI login attempts per username and enforces a temporary lockout once a threshold is
 * reached. Backed by the shared {@link Cache} so the count is consistent across multiple Philter
 * instances (when Valkey/Redis is configured) and expires automatically after the lockout window.
 *
 * <p>The attempt counter is stored with a sliding time-to-live equal to the lockout window: while an
 * account is locked, the window keeps resetting on each further attempt, and once attempts stop the
 * lock clears on its own.
 */
public class LoginAttemptCache extends Cache {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptCache.class);

    /** Number of consecutive failures that triggers a lockout. Override with LOGIN_MAX_ATTEMPTS. */
    private final int maxAttempts;

    /** Lockout window in seconds. Override with LOGIN_LOCKOUT_SECONDS. */
    private final int lockoutSeconds;

    public LoginAttemptCache(final String host, final int port, final String password, final boolean ssl) {
        super(host, port, password, ssl);
        this.maxAttempts = EnvUtils.getInt("LOGIN_MAX_ATTEMPTS", 5);
        this.lockoutSeconds = EnvUtils.getInt("LOGIN_LOCKOUT_SECONDS", 900);
    }

    /**
     * Records a failed login for the given username and returns the new consecutive-failure count.
     */
    public long recordFailure(final String username) {

        final String key = buildKey(username);

        long count = 0;
        final String existing = backend.get(key);
        if (existing != null) {
            try {
                count = Long.parseLong(existing);
            } catch (final NumberFormatException ex) {
                count = 0;
            }
        }

        count++;
        backend.setex(key, lockoutSeconds, Long.toString(count));

        if (count >= maxAttempts) {
            LOGGER.warn("Username '{}' is now locked out after {} failed login attempts.", username, count);
        }

        return count;

    }

    /** Clears the failure count for the username (called on a successful login). */
    public void reset(final String username) {
        backend.del(buildKey(username));
    }

    /** Returns whether the username is currently locked out. */
    public boolean isLocked(final String username) {

        final String existing = backend.get(buildKey(username));
        if (existing == null) {
            return false;
        }

        try {
            return Long.parseLong(existing) >= maxAttempts;
        } catch (final NumberFormatException ex) {
            return false;
        }

    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getLockoutSeconds() {
        return lockoutSeconds;
    }

    private String buildKey(final String username) {
        // Lower-case so lookups are case-insensitive, matching how usernames (emails) are compared.
        return "login_attempts_" + (username == null ? "" : username.toLowerCase());
    }

}
