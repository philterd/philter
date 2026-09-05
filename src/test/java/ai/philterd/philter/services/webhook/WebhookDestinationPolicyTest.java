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

import static org.junit.jupiter.api.Assertions.assertFalse;
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

}
