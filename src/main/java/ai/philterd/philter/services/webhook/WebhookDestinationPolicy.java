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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides where a webhook may be delivered. The destination is chosen by a user but the policy is set
 * by an administrator, so this is what stops one account aiming Philter at another's endpoint or at
 * infrastructure only Philter can reach.
 *
 * <p>Two questions, because a host is not a destination: {@link #isHostAllowed} answers the one a user
 * can be told about while editing a URL, and {@link #isAddressAllowed} answers the one that matters, at
 * the moment of connection, after DNS has had its say.
 *
 * <p>An empty allowlist permits any public address and refuses loopback, link-local and private ones,
 * so a deployment that configures nothing still cannot be aimed at its own network.
 */
public final class WebhookDestinationPolicy {

    private final List<String> hosts = new ArrayList<>();
    private final List<Cidr> ranges = new ArrayList<>();

    public WebhookDestinationPolicy(final String allowlist) {

        if (allowlist == null || allowlist.isBlank()) {
            return;
        }

        for (String entry : allowlist.split(",")) {

            entry = entry.trim();

            if (entry.isEmpty()) {
                continue;
            }

            final Cidr range = Cidr.parse(entry);

            if (range != null) {
                ranges.add(range);
            } else {
                hosts.add(entry.toLowerCase(Locale.ROOT));
            }

        }

    }

    /** Whether the allowlist is empty, in which case only the public-address rule applies. */
    public boolean isEmpty() {
        return hosts.isEmpty() && ranges.isEmpty();
    }

    /** Whether this host may be used at all. Checked when a URL is saved and again before delivery. */
    public boolean isHostAllowed(final String host) {

        if (host == null || host.isBlank()) {
            return false;
        }

        if (isEmpty()) {
            return true;
        }

        final String normalized = host.toLowerCase(Locale.ROOT);

        if (hosts.contains(normalized)) {
            return true;
        }

        try {
            return matchesRange(InetAddress.getByName(bare(normalized)));
        } catch (final UnknownHostException notAnAddress) {
            return false;
        }

    }

    /**
     * The whole question, for a caller that can afford a lookup: the host, and where it currently
     * points. {@link #isHostAllowed} alone answers true for any host when no allowlist is set, since it
     * is the address that decides there; this is what a form needs in order to refuse a private literal.
     * An unresolvable host is permitted, because delivery enforces the rule again anyway.
     */
    public boolean isDestinationAllowed(final String host) {

        if (!isHostAllowed(host)) {
            return false;
        }

        try {
            for (final InetAddress address : InetAddress.getAllByName(bare(host))) {
                if (!isAddressAllowed(address)) {
                    return false;
                }
            }
        } catch (final UnknownHostException cannotResolveYet) {
            return true;
        }

        return true;

    }

    /** A URL's host keeps the brackets around an IPv6 literal; InetAddress wants it without them. */
    private static String bare(final String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    /**
     * Whether a connection may be made to this resolved address. An address an administrator listed is
     * permitted whatever it is; anything else must be public, which is what makes a redirect or a
     * rebound DNS answer land somewhere harmless.
     */
    public boolean isAddressAllowed(final InetAddress address) {

        if (address == null) {
            return false;
        }

        return matchesRange(address) || isPublic(address);

    }

    private boolean matchesRange(final InetAddress address) {
        for (final Cidr range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublic(final InetAddress address) {
        return !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isAnyLocalAddress()
                && !address.isMulticastAddress()
                && !isUniqueLocalIpv6(address);
    }

    /** fc00::/7, which isSiteLocalAddress does not cover. */
    private static boolean isUniqueLocalIpv6(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** An address and prefix length, matched by comparing the leading bits. */
    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(final String entry) {

            final int slash = entry.indexOf('/');
            final String host = slash < 0 ? entry : entry.substring(0, slash);

            final byte[] address;
            try {
                address = literalAddress(host);
            } catch (final UnknownHostException notAnAddress) {
                return null;
            }

            if (address == null) {
                return null;
            }

            int prefix = address.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (final NumberFormatException ex) {
                    return null;
                }
                if (prefix < 0 || prefix > address.length * 8) {
                    return null;
                }
            }

            return new Cidr(address, prefix);

        }

        /** Parses only a literal address: a hostname must not be resolved into a range here. */
        private static byte[] literalAddress(final String host) throws UnknownHostException {
            if (!host.matches("^[0-9.]+$") && !host.contains(":")) {
                return null;
            }
            return InetAddress.getByName(host).getAddress();
        }

        boolean contains(final InetAddress candidate) {

            final byte[] bytes = candidate.getAddress();

            if (bytes.length != network.length) {
                return false;
            }

            for (int bit = 0; bit < prefixBits; bit++) {
                final int index = bit / 8;
                final int mask = 1 << (7 - (bit % 8));
                if ((bytes[index] & mask) != (network[index] & mask)) {
                    return false;
                }
            }

            return true;

        }

    }

}
