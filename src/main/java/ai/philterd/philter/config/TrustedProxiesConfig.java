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
package ai.philterd.philter.config;

import ai.philterd.philter.utils.IpAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The proxies whose X-Forwarded-For header Philter believes, from {@code TRUSTED_PROXIES}: a
 * comma-separated list of IP addresses and CIDR ranges. When it is unset, loopback, private, link-local,
 * and IPv6 unique-local addresses are trusted, which covers a load balancer or ingress on the same
 * network without configuration while ignoring the header from a client on the internet.
 */
public final class TrustedProxiesConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrustedProxiesConfig.class);

    public static final String DEFAULT =
            "127.0.0.0/8, ::1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, fc00::/7, fe80::/10";

    private static volatile List<IpAddresses.Cidr> configured;

    // Test-only override: when non-null it is used instead of the environment variable.
    private static volatile List<IpAddresses.Cidr> overrideForTesting;

    private TrustedProxiesConfig() {
    }

    public static boolean isTrusted(final InetAddress address) {
        for (final IpAddresses.Cidr range : ranges()) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<IpAddresses.Cidr> ranges() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        if (configured == null) {
            final String value = System.getenv("TRUSTED_PROXIES");
            configured = parse(value == null || value.isBlank() ? DEFAULT : value);
        }
        return configured;
    }

    /** The ranges in a comma-separated list. An entry that is not one is logged and left out. */
    static List<IpAddresses.Cidr> parse(final String value) {
        final List<IpAddresses.Cidr> ranges = new ArrayList<>();
        for (String entry : value.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            final IpAddresses.Cidr range = IpAddresses.Cidr.parse(entry);
            if (range == null) {
                LOGGER.warn("Ignoring TRUSTED_PROXIES entry '{}': not an IP address or CIDR range.", entry);
            } else {
                ranges.add(range);
            }
        }
        return List.copyOf(ranges);
    }

    /** Test hook: trust these ranges, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final String value) {
        overrideForTesting = value == null ? null : parse(value);
    }

}
