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
package ai.philterd.philter.utils;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressesTest {

    @Test
    void parsesOnlyFullLiterals() {
        assertNotNull(IpAddresses.parseLiteral("203.0.113.7"));
        assertNotNull(IpAddresses.parseLiteral("2001:db8::1"));
        assertNotNull(IpAddresses.parseLiteral("::ffff:192.0.2.1"));
        // InetAddress.ofLiteral would take these shorthand forms; an address in a header or list is not one.
        assertNull(IpAddresses.parseLiteral("10"));
        assertNull(IpAddresses.parseLiteral("10.1"));
        assertNull(IpAddresses.parseLiteral("999.1.1.1"));
        assertNull(IpAddresses.parseLiteral("example.com"));
        assertNull(IpAddresses.parseLiteral(""));
        assertNull(IpAddresses.parseLiteral(null));
    }

    @Test
    void matchesRanges() throws Exception {
        final IpAddresses.Cidr ten = IpAddresses.Cidr.parse("10.0.0.0/8");
        assertTrue(ten.contains(InetAddress.ofLiteral("10.200.1.1")));
        assertFalse(ten.contains(InetAddress.ofLiteral("11.0.0.1")));
        assertFalse(ten.contains(InetAddress.ofLiteral("::1")), "an IPv6 address is never in an IPv4 range");
        assertTrue(IpAddresses.Cidr.parse("fc00::/7").contains(InetAddress.ofLiteral("fd12::1")));
        assertTrue(IpAddresses.Cidr.parse("198.51.100.7").contains(InetAddress.ofLiteral("198.51.100.7")));
        assertNull(IpAddresses.Cidr.parse("10.0.0.0/33"));
        assertNull(IpAddresses.Cidr.parse("example.com/8"));
    }

}
