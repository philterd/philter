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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignInThrottleTest {

    private static final int MAX_FAILURES = 3;
    private static final int LOCKOUT_SECONDS = 60;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final UserService userService = mock(UserService.class);
    private final SignInThrottle throttle = new SignInThrottle("", 0, "", false, userService, now::get, MAX_FAILURES,
            LOCKOUT_SECONDS, 20);

    @Test
    @DisplayName("An IPv6 client is counted by its /64, and an IPv4 client by its address")
    void ipv6ClientsAreCountedByNetwork() {
        assertEquals("2001:db8:1:2:0:0:0:0/64", SignInThrottle.client("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        assertEquals(SignInThrottle.client("2001:db8:1:2::1"), SignInThrottle.client("2001:db8:1:2:ffff::9"));
        assertFalse(SignInThrottle.client("2001:db8:1:2::1").equals(SignInThrottle.client("2001:db8:1:3::1")));
        assertEquals("203.0.113.7", SignInThrottle.client("203.0.113.7"));
    }

    @Test
    @DisplayName("Requests from addresses in one IPv6 /64 share one rate count, so rotating addresses gains nothing")
    void rotatingIpv6AddressesSharesOneCount() {
        final String network = "2001:db8:" + Integer.toHexString(Math.abs(UUID.randomUUID().hashCode()) & 0xffff) + ":1:";
        long count = 0;
        for (int i = 1; i <= 25; i++) {
            count = throttle.countRequest(network + Integer.toHexString(i) + "::1");
        }
        assertEquals(25, count, "25 different addresses in one /64 are one client");
    }

    @Test
    @DisplayName("A username with no account locks after the same failures as an account, so it is refused the same way")
    void anUnknownUsernameLocksLikeAnAccount() {
        when(userService.findByUsername(anyString())).thenReturn(null);
        final SignInThrottle.Account unknown = throttle.account("nobody-" + UUID.randomUUID());
        assertFalse(unknown.isLocked());
        for (int attempt = 1; attempt <= MAX_FAILURES; attempt++) {
            assertEquals(attempt, unknown.beginAttempt());
            assertEquals(attempt == MAX_FAILURES, throttle.recordFailure(unknown, attempt));
        }
        assertTrue(unknown.isLocked());
    }

    @Test
    @DisplayName("An account's failures and lock are kept with the user, not in the cache")
    void anAccountIsCountedAndLockedWithTheUser() {
        final UserEntity user = new UserEntity();
        user.setId("user-1");
        when(userService.findByUsername("jordan")).thenReturn(user);
        when(userService.beginSignInAttempt("user-1", now.get(), LOCKOUT_SECONDS * 1000L)).thenReturn(3L);
        when(userService.isSignInLocked("user-1", now.get())).thenReturn(true);

        final SignInThrottle.Account account = throttle.account("jordan");
        assertEquals(3L, account.beginAttempt());
        assertTrue(throttle.recordFailure(account, 3));
        verify(userService).lockSignIn("user-1", now.get() + LOCKOUT_SECONDS * 1000L);
        assertTrue(account.isLocked());
        account.reset();
        verify(userService).resetSignInAttempts("user-1");
    }

}
