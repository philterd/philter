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
package ai.philterd.philter.api.filters.auth;

import org.apache.commons.net.util.SubnetUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApiAuthenticationFilterTest {

    @Test
    public void emptyAllowlistAllowsAll() {
        final List<SubnetUtils.SubnetInfo> allowlist = ApiAuthenticationFilter.parseIpAllowlist("");
        assertTrue(allowlist.isEmpty());
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "203.0.113.9"));
        // An empty allowlist allows even a null/unknown address.
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, null));
    }

    @Test
    public void cidrRangeMatches() {
        final List<SubnetUtils.SubnetInfo> allowlist = ApiAuthenticationFilter.parseIpAllowlist("10.0.0.0/8");
        assertEquals(1, allowlist.size());
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "10.1.2.3"));
        assertFalse(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "11.0.0.1"));
    }

    @Test
    public void bareAddressIsTreatedAsSingleHost() {
        final List<SubnetUtils.SubnetInfo> allowlist = ApiAuthenticationFilter.parseIpAllowlist("192.168.1.5, 172.16.0.0/12");
        assertEquals(2, allowlist.size());
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "192.168.1.5"));
        assertFalse(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "192.168.1.6"));
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "172.16.5.5"));
    }

    @Test
    public void configuredAllowlistDeniesNullMalformedAndIpv6() {
        final List<SubnetUtils.SubnetInfo> allowlist = ApiAuthenticationFilter.parseIpAllowlist("10.0.0.0/8");
        assertFalse(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, null));
        assertFalse(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "not-an-ip"));
        assertFalse(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "::1"));
    }

    @Test
    public void invalidEntriesAreSkipped() {
        final List<SubnetUtils.SubnetInfo> allowlist = ApiAuthenticationFilter.parseIpAllowlist("bogus, 10.0.0.0/8, ");
        assertEquals(1, allowlist.size());
        assertTrue(ApiAuthenticationFilter.isIpAddressAllowed(allowlist, "10.9.9.9"));
    }

}
