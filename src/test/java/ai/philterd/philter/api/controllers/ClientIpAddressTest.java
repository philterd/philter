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
package ai.philterd.philter.api.controllers;

import ai.philterd.philter.config.TrustedProxiesConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link AbstractApiController#getClientIpAddress}, with the default trusted proxies unless a test says otherwise. */
class ClientIpAddressTest {

    @AfterEach
    void clearOverride() {
        TrustedProxiesConfig.setOverrideForTesting(null);
    }

    private static String client(final String remote, final String... forwardedFor) {
        TrustedProxiesConfig.setOverrideForTesting(TrustedProxiesConfig.DEFAULT);
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (final String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return AbstractApiController.getClientIpAddress(request);
    }

    @Test
    @DisplayName("Without the header, or from an untrusted address, the connection's address is recorded")
    void untrustedOrAbsent() {
        assertEquals("10.0.0.5", client("10.0.0.5"));
        assertEquals("203.0.113.9", client("203.0.113.9", "198.51.100.7"), "an internet client cannot choose its address");
    }

    @Test
    @DisplayName("From a trusted proxy, an IPv4 or IPv6 client address is recorded")
    void trustedProxy() {
        assertEquals("198.51.100.7", client("10.0.0.5", "198.51.100.7"));
        assertEquals("2001:db8::1", client("::1", "2001:db8::1"));
        assertEquals("198.51.100.7", client("fd00::2", "198.51.100.7"));
    }

    @Test
    @DisplayName("A port is removed, for IPv4 and bracketed IPv6")
    void ports() {
        assertEquals("198.51.100.7", client("10.0.0.5", "198.51.100.7:51234"));
        assertEquals("2001:db8::1", client("10.0.0.5", "[2001:db8::1]:443"));
        assertEquals("2001:db8::1", client("10.0.0.5", "[2001:db8::1]"));
    }

    @Test
    @DisplayName("A hostname, text, a formula, or a malformed address is not recorded")
    void notAnAddress() {
        for (final String value : new String[]{"client.example.com", "unknown", "=HYPERLINK(\"https://x.example\",\"y\")",
                "999.1.1.1", "10", "198.51.100.7:port", "[2001:db8::1]x", ""}) {
            assertEquals("10.0.0.5", client("10.0.0.5", value), value);
        }
    }

    @Test
    @DisplayName("The chain is read from the right, so a client cannot choose its address through a trusted proxy")
    void readsFromTheRight() {
        // The proxy appended 198.51.100.7 to a header the client wrote; the client's own entry is ignored.
        assertEquals("198.51.100.7", client("10.0.0.5", "6.6.6.6, 198.51.100.7"));
        // Trusted proxies along the way are skipped.
        assertEquals("198.51.100.7", client("10.0.0.5", "198.51.100.7, 10.0.0.9, 192.168.1.4"));
        // Several header lines are one list.
        assertEquals("198.51.100.7", client("10.0.0.5", "6.6.6.6", "198.51.100.7, 10.0.0.9"));
        // An internal client behind internal proxies is recorded as itself.
        assertEquals("10.0.0.8", client("10.0.0.5", "10.0.0.8, 10.0.0.9"));
        // Text where the client's address should be stops the walk.
        assertEquals("10.0.0.5", client("10.0.0.5", "198.51.100.7, unknown"));
    }

    @Test
    @DisplayName("TRUSTED_PROXIES replaces the default")
    void configuredProxies() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        TrustedProxiesConfig.setOverrideForTesting("203.0.113.0/24, not-a-range");
        assertEquals("10.0.0.5", AbstractApiController.getClientIpAddress(request), "10.0.0.5 is no longer trusted");

        request.setRemoteAddr("203.0.113.20");
        assertEquals("198.51.100.7", AbstractApiController.getClientIpAddress(request));
    }

}
