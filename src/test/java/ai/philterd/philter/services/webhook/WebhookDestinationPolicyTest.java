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
package ai.philterd.philter.services.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookDestinationPolicyTest {

    private static InetAddress address(final String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("With no allowlist, a public address is permitted and a local one is not")
    void anEmptyAllowlistStillRefusesLocalAddresses() throws Exception {

        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("");

        assertTrue(policy.isAddressAllowed(address("93.184.216.34")));

        assertFalse(policy.isAddressAllowed(address("127.0.0.1")));
        assertFalse(policy.isAddressAllowed(address("10.1.2.3")));
        assertFalse(policy.isAddressAllowed(address("192.168.0.5")));
        assertFalse(policy.isAddressAllowed(address("172.16.0.1")));
        assertFalse(policy.isAddressAllowed(address("::1")));
        assertFalse(policy.isAddressAllowed(address("fd00::1")));
    }

    @Test
    @DisplayName("The cloud metadata address is refused by default")
    void theMetadataAddressIsRefused() throws Exception {
        assertFalse(new WebhookDestinationPolicy("").isAddressAllowed(address("169.254.169.254")));
    }

    @Test
    @DisplayName("Every special-purpose range is refused, including in IPv6 forms that embed an IPv4 address")
    void specialPurposeRangesAreRefused() throws Exception {
        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("");
        for (final String refused : java.util.List.of(
                // "This network", shared address space (100.64/10, which holds Alibaba Cloud's metadata service), IETF
                // protocol assignments, the 6to4 relay, benchmarking, documentation, reserved, and broadcast.
                "0.1.2.3", "100.64.0.1", "100.100.100.200", "100.127.255.255", "192.0.0.192", "192.88.99.1",
                "198.18.0.1", "198.19.255.255", "192.0.2.1", "198.51.100.1", "203.0.113.1", "240.0.0.1",
                "255.255.255.255", "224.0.0.1",
                // IPv6 forms that lead to IPv4: IPv4-compatible, NAT64 (well-known and local-use), 6to4, and Teredo.
                "::10.0.0.1", "::127.0.0.1", "64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe",
                "64:ff9b:1::a00:1", "2002:a00:1::1", "2002:7f00:1::1", "2001:0:4136:e378:8000:63bf:f5ff:fffe",
                // IPv6 special-purpose space: discard, benchmarking, documentation, site-local, multicast, AWS metadata.
                "100::1", "2001:2::1", "2001:db8::1", "3fff::1", "fec0::1", "ff02::1", "fd00:ec2::254", "fe80::1")) {
            assertFalse(policy.isAddressAllowed(address(refused)), refused + " must be refused");
        }
    }

    @Test
    @DisplayName("Public addresses next to the special-purpose ranges, and public IPv4 reached over IPv6, are permitted")
    void publicAddressesArePermitted() throws Exception {
        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("");
        for (final String allowed : java.util.List.of("8.8.8.8", "100.63.255.255", "100.128.0.0", "198.17.255.255",
                "198.20.0.0", "172.32.0.1", "192.0.1.1", "223.255.255.254", "2606:4700:4700::1111",
                // NAT64 and 6to4 of a public address: how an IPv6-only network reaches an IPv4 endpoint.
                "64:ff9b::808:808", "2002:808:808::1")) {
            assertTrue(policy.isAddressAllowed(address(allowed)), allowed + " must be permitted");
        }
    }

    @Test
    @DisplayName("An administrator can still list a special-purpose range")
    void aListedSpecialPurposeRangeIsPermitted() throws Exception {
        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("100.64.0.0/10");
        assertTrue(policy.isAddressAllowed(address("100.70.1.2")));
        assertFalse(policy.isAddressAllowed(address("198.18.0.1")), "only what is listed");
    }

    @Test
    @DisplayName("With an allowlist, only listed hosts may be used")
    void anAllowlistAdmitsOnlyWhatItNames() {

        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("hooks.example.com, 203.0.113.0/24");

        assertTrue(policy.isHostAllowed("hooks.example.com"));
        assertTrue(policy.isHostAllowed("HOOKS.EXAMPLE.COM"), "a host is matched case-insensitively");
        assertTrue(policy.isHostAllowed("203.0.113.7"));

        assertFalse(policy.isHostAllowed("attacker.example.net"));
        assertFalse(policy.isHostAllowed("203.0.114.7"), "an address outside the range is refused");
    }

    @Test
    @DisplayName("An administrator may list an internal range, and only that range")
    void aListedInternalRangeIsPermitted() throws Exception {

        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("10.4.0.0/16");

        // Listed, so permitted even though it is private: this is the internal-collector case.
        assertTrue(policy.isAddressAllowed(address("10.4.7.9")));

        // Another private address is still refused, so listing one does not open the rest.
        assertFalse(policy.isAddressAllowed(address("10.5.7.9")));
        assertFalse(policy.isAddressAllowed(address("127.0.0.1")));
    }

    @Test
    @DisplayName("An exact address entry does not admit its neighbours")
    void anExactAddressEntryIsASingleHost() throws Exception {

        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("10.4.0.9");

        assertTrue(policy.isAddressAllowed(address("10.4.0.9")));
        assertFalse(policy.isAddressAllowed(address("10.4.0.10")));
    }

    @Test
    @DisplayName("A host that is allowed may still resolve somewhere that is not")
    void beingAllowedAsAHostIsNotEnough() throws Exception {

        // The rebinding case: the name passes, the address it resolves to does not.
        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("hooks.example.com");

        assertTrue(policy.isHostAllowed("hooks.example.com"));
        assertFalse(policy.isAddressAllowed(address("169.254.169.254")),
                "the address check is what a rebound name has to get past");
    }


    @Test
    @DisplayName("A form is told about a private literal, which the host check alone cannot answer")
    void aPrivateLiteralIsRefusedAsADestination() {

        final WebhookDestinationPolicy policy = new WebhookDestinationPolicy("");

        // isHostAllowed says yes with no allowlist, because there it is the address that decides.
        assertTrue(policy.isHostAllowed("127.0.0.1"));

        assertFalse(policy.isDestinationAllowed("127.0.0.1"));
        assertFalse(policy.isDestinationAllowed("169.254.169.254"));
        assertFalse(policy.isDestinationAllowed("10.1.2.3"));
        assertFalse(policy.isDestinationAllowed("[::1]"), "an IPv6 literal keeps its brackets in a URL");
        assertFalse(policy.isDestinationAllowed("[fd00::1]"));
    }

    @Test
    @DisplayName("A host that cannot be resolved is left to delivery to judge")
    void anUnresolvableHostIsNotRefusedAtSaveTime() {
        assertTrue(new WebhookDestinationPolicy("").isDestinationAllowed("not-a-real-host.invalid"));
    }

    @Test
    @DisplayName("An allowlist entry must be a hostname, an IP address, or a CIDR range")
    void validatesAllowlistEntries() {
        assertNull(WebhookDestinationPolicy.invalidEntry(null));
        assertNull(WebhookDestinationPolicy.invalidEntry(""));
        assertNull(WebhookDestinationPolicy.invalidEntry(
                "hooks.example.com, localhost, 203.0.113.7, 10.4.0.0/16, 2001:db8::1, 2001:db8::/32, , "));

        assertEquals("10.0.0.0/99", WebhookDestinationPolicy.invalidEntry("hooks.example.com, 10.0.0.0/99"));
        assertEquals("999.1.1.1", WebhookDestinationPolicy.invalidEntry("999.1.1.1"));
        assertEquals("hooks.example.com/24", WebhookDestinationPolicy.invalidEntry("hooks.example.com/24"));
        assertEquals("-bad.example.com", WebhookDestinationPolicy.invalidEntry("-bad.example.com"));
        assertEquals("https://hooks.example.com", WebhookDestinationPolicy.invalidEntry("https://hooks.example.com"));
        assertEquals("has space.example.com", WebhookDestinationPolicy.invalidEntry("has space.example.com"));
    }

}
