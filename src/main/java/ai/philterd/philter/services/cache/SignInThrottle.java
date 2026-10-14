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

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.utils.EnvUtils;
import ai.philterd.philter.utils.IpAddresses;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Protects password sign-in against guessing: locks an account after consecutive failed sign-ins, and limits how many
 * sign-in requests a client may make each minute.
 *
 * <p>An account's failures and lock are stored with the user, so they hold across instances without a shared cache and
 * cannot be pushed out of one. A username with no account is counted the same way in a cache instead, so the responses
 * do not tell which usernames exist. That count, and the per-client rate limit, are kept in their own store when no
 * shared cache is configured, apart from cached data; a store that fills drops its oldest counts rather than refusing
 * everyone, which at worst weakens the rate limit and never unlocks an account early. IPv6 clients are counted by /64,
 * since one network holds that many addresses. Usernames and addresses are hashed into the keys, so a long or crafted
 * value cannot bloat or collide with them.
 */
public class SignInThrottle extends Cache {

    /** The rate limit counts requests per address in windows of this many seconds. */
    public static final int RATE_WINDOW_SECONDS = 60;

    /** The store sign-in counts use without a shared cache: one per process, apart from cached data. */
    private static final InMemoryCacheBackend IN_MEMORY = InMemoryCacheBackend.newStore();

    private final UserService userService;
    private final LongSupplier clock;
    private final int maxFailures;
    private final int lockoutSeconds;
    private final int ratePerMinute;

    public SignInThrottle(final String host, final int port, final String password, final boolean ssl,
                          final UserService userService) {
        this(host, port, password, ssl, userService, System::currentTimeMillis, positive("SIGN_IN_MAX_FAILURES", 5),
                positive("SIGN_IN_LOCKOUT_MINUTES", 15) * 60, positive("SIGN_IN_RATE_LIMIT_PER_MINUTE", 20));
    }

    /** With explicit limits and clock rather than the environment's, for tests. */
    public SignInThrottle(final String host, final int port, final String password, final boolean ssl,
                          final UserService userService, final LongSupplier clock,
                          final int maxFailures, final int lockoutSeconds, final int ratePerMinute) {
        super(host, port, password, ssl, IN_MEMORY);
        this.userService = userService;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.lockoutSeconds = lockoutSeconds;
        this.ratePerMinute = ratePerMinute;
    }

    /**
     * The sign-in limits for one username: its account's, stored with the user, or, when no account has that
     * username, a count kept in the cache so that it is refused the same way.
     */
    public Account account(final String username) {
        final UserEntity user = username == null || username.isBlank() ? null : userService.findByUsername(username);
        return user == null ? new UnknownAccount(username) : new StoredAccount(user.getId());
    }

    /** The failures and lock of one username. */
    public interface Account {

        /** Whether sign-in is refused, before the password is checked. */
        boolean isLocked();

        /**
         * Counts an attempt before its password is checked, so parallel requests cannot all get past the limit before
         * any failure is recorded: at most {@code maxFailures} passwords are checked in a window, however the requests
         * interleave.
         *
         * @return this attempt's number in the window; past {@link #getMaxFailures()}, refuse it unchecked.
         */
        long beginAttempt();

        /** Locks sign-in for the lockout period, measured from now. */
        void lock();

        /** Clears the count after a sign-in whose password was right. */
        void reset();

    }

    private final class StoredAccount implements Account {

        private final String userId;

        StoredAccount(final String userId) {
            this.userId = userId;
        }

        @Override
        public boolean isLocked() {
            return userService.isSignInLocked(userId, clock.getAsLong());
        }

        @Override
        public long beginAttempt() {
            return userService.beginSignInAttempt(userId, clock.getAsLong(), lockoutSeconds * 1000L);
        }

        @Override
        public void lock() {
            userService.lockSignIn(userId, clock.getAsLong() + lockoutSeconds * 1000L);
        }

        @Override
        public void reset() {
            userService.resetSignInAttempts(userId);
        }

    }

    private final class UnknownAccount implements Account {

        private final String username;

        UnknownAccount(final String username) {
            this.username = username;
        }

        @Override
        public boolean isLocked() {
            return backend.exists(lockKey(username));
        }

        @Override
        public long beginAttempt() {
            return backend.incrementInWindow(failuresKey(username), lockoutSeconds);
        }

        @Override
        public void lock() {
            backend.setex(lockKey(username), lockoutSeconds, "1");
        }

        @Override
        public void reset() {
            backend.del(failuresKey(username));
        }

    }

    /**
     * Records that an attempt failed. The attempt that reaches the limit locks the account for the lockout period,
     * measured from then; attempts while locked are refused without being counted, so the lock ends on time. The count
     * is left to expire with its window rather than cleared: a request already past the lock check would otherwise
     * start a fresh count and get a password check the limit should have refused.
     *
     * @return {@code true} if this attempt locked the account, so the caller audits the lock once.
     */
    public boolean recordFailure(final Account account, final long attempt) {
        if (attempt < maxFailures) {
            return false;
        }
        account.lock();
        return attempt == maxFailures;
    }

    /**
     * Counts a sign-in request from the address in the current one-minute window. IPv6 addresses are counted by their
     * /64, which a single network holds.
     *
     * @return how many requests the address has made in the window, including this one.
     */
    public long countRequest(final String clientIp) {
        return backend.incrementInWindow(rateKey(client(clientIp)), RATE_WINDOW_SECONDS);
    }

    /** The client a rate is counted for: an IPv6 address's /64, and any other address as it is. */
    static String client(final String clientIp) {
        final InetAddress address = clientIp == null ? null : IpAddresses.parseLiteral(clientIp);
        if (address instanceof Inet6Address) {
            final byte[] prefix = Arrays.copyOf(address.getAddress(), 16);
            Arrays.fill(prefix, 8, 16, (byte) 0);
            try {
                return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
            } catch (final java.net.UnknownHostException cannotHappen) {
                return clientIp;
            }
        }
        return clientIp;
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

    private static String rateKey(final String client) {
        return "sign_in_rate_" + hash(client);
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
