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

import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.utils.EnvUtils;

/**
 * Protects password sign-in against guessing: locks a username after consecutive failed sign-ins, and
 * limits how many sign-in requests a client IP address may make each minute. Counted in the shared
 * cache when one is configured, so the limits hold across instances; with the in-memory cache they are
 * per instance. Usernames and addresses are hashed into the keys, so a long or crafted value cannot
 * bloat or collide with them.
 */
public class SignInThrottle extends Cache {

    /** The rate limit counts requests per address in windows of this many seconds. */
    public static final int RATE_WINDOW_SECONDS = 60;

    private final int maxFailures;
    private final int lockoutSeconds;
    private final int ratePerMinute;

    public SignInThrottle(final String host, final int port, final String password, final boolean ssl) {
        this(host, port, password, ssl, positive("SIGN_IN_MAX_FAILURES", 5),
                positive("SIGN_IN_LOCKOUT_MINUTES", 15) * 60, positive("SIGN_IN_RATE_LIMIT_PER_MINUTE", 20));
    }

    /** With explicit limits rather than the environment's, for tests. */
    public SignInThrottle(final String host, final int port, final String password, final boolean ssl,
                          final int maxFailures, final int lockoutSeconds, final int ratePerMinute) {
        super(host, port, password, ssl);
        this.maxFailures = maxFailures;
        this.lockoutSeconds = lockoutSeconds;
        this.ratePerMinute = ratePerMinute;
    }

    /** Whether sign-in for this username is refused, before its password is checked. */
    public boolean isLocked(final String username) {
        // A failure that could not be counted for lack of room must not let anyone through.
        return backend.exists(lockKey(username)) || backend.counterCapacityExceeded();
    }

    /**
     * Counts an attempt before its password is checked, so parallel requests cannot all get past the
     * limit before any failure is recorded: at most {@code maxFailures} passwords are checked per
     * username in a window, however the requests interleave.
     *
     * @return this attempt's number in the window; past {@link #getMaxFailures()}, refuse it unchecked.
     */
    public long beginAttempt(final String username) {
        return backend.incrementInWindow(failuresKey(username), lockoutSeconds);
    }

    /**
     * Records that an attempt failed. The attempt that reaches the limit locks the username for the
     * lockout period, measured from then; attempts while locked are refused without being counted, so
     * the lock ends on time.
     *
     * @return {@code true} if this attempt locked the username, so the caller audits the lock once.
     */
    public boolean recordFailure(final String username, final long attempt) {
        if (attempt < maxFailures) {
            return false;
        }
        lock(username);
        return attempt == maxFailures;
    }

    /**
     * Locks the username for the lockout period. The count is left to expire with its window rather
     * than deleted: a request already past the lock check would otherwise start a fresh count and get a
     * password check the limit should have refused. The window began at the first attempt, so it ends
     * before the lock does.
     */
    public void lock(final String username) {
        backend.setex(lockKey(username), lockoutSeconds, "1");
    }

    /** Clears the count after a sign-in whose password was right. */
    public void reset(final String username) {
        backend.del(failuresKey(username));
    }

    /**
     * Counts a sign-in request from the address in the current one-minute window.
     *
     * @return how many requests the address has made in the window, including this one.
     */
    public long countRequest(final String clientIp) {
        return backend.incrementInWindow(rateKey(clientIp), RATE_WINDOW_SECONDS);
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    public int getLockoutSeconds() {
        return lockoutSeconds;
    }

    public int getRatePerMinute() {
        return ratePerMinute;
    }

    private static String failuresKey(final String username) {
        return "sign_in_failures_" + hash(username);
    }

    private static String lockKey(final String username) {
        return "sign_in_lock_" + hash(username);
    }

    private static String rateKey(final String clientIp) {
        return "sign_in_rate_" + hash(clientIp);
    }

    private static String hash(final String value) {
        return EncryptionService.hashSha256(value == null ? "" : value);
    }

    private static int positive(final String name, final int defaultValue) {
        final int value = EnvUtils.getInt(name, defaultValue);
        if (value <= 0) {
            throw new IllegalStateException(name + " must be a positive number.");
        }
        return value;
    }

}
