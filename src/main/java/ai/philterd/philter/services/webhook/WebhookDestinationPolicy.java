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

import ai.philterd.philter.utils.IpAddresses;

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
 * <p>An empty allowlist permits any public address and refuses every special-purpose one (private, shared,
 * loopback, link-local, reserved and the like, including in IPv6 forms that embed an IPv4 address), so a deployment
 * that configures nothing still cannot be aimed at its own network.
 */
public final class WebhookDestinationPolicy {

    private final List<String> hosts = new ArrayList<>();
    private final List<IpAddresses.Cidr> ranges = new ArrayList<>();

    public WebhookDestinationPolicy(final String allowlist) {

        if (allowlist == null || allowlist.isBlank()) {
            return;
        }

        for (String entry : allowlist.split(",")) {

            entry = entry.trim();

            if (entry.isEmpty()) {
                continue;
            }

            final IpAddresses.Cidr range = IpAddresses.Cidr.parse(entry);

            if (range != null) {
                ranges.add(range);
            } else {
                hosts.add(entry.toLowerCase(Locale.ROOT));
            }

        }

    }

    /** A DNS hostname: dot-separated labels of letters, digits, and inner hyphens. */
    private static final java.util.regex.Pattern HOSTNAME = java.util.regex.Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    /**
     * The first entry of a comma-separated allowlist that is not a hostname, an IP address, or a CIDR
     * range, or {@code null} when every entry is one. Nothing is looked up in DNS.
     */
    public static String invalidEntry(final String allowlist) {

        if (allowlist == null) {
            return null;
        }

        for (String entry : allowlist.split(",")) {

            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            final int slash = entry.indexOf('/');
            final String host = slash < 0 ? entry : entry.substring(0, slash);

            final boolean literal = isLiteral(host);
            if (slash >= 0 ? !literal || IpAddresses.Cidr.parse(entry) == null
                    : !literal && (host.matches("^[0-9.]+$") || !HOSTNAME.matcher(host).matches())) {
                return entry;
            }

        }

        return null;

    }

    private static boolean isLiteral(final String host) {
        return IpAddresses.parseLiteral(host) != null;
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
        for (final IpAddresses.Cidr range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The IANA special-purpose ranges a webhook may not reach unless an administrator lists them: private, shared,
     * loopback, link-local, reserved, documentation, benchmarking, and multicast space, which on a given network can
     * lead to internal services, such as a cloud's instance metadata service at 100.100.100.200. IPv6 forms that
     * embed an IPv4 address are judged by that address instead; see {@link #embeddedIpv4}.
     */
    private static final List<IpAddresses.Cidr> SPECIAL_PURPOSE = java.util.stream.Stream.of(
            // IPv4.
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
            "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
            // IPv6: unspecified, loopback and IPv4-compatible; local-use NAT64, whose embedding is network-specific;
            // discard; Teredo, whose embedded address is obscured; benchmarking; ORCHID; documentation;
            // unique-local; link-local; site-local; multicast.
            "::/96", "64:ff9b:1::/48", "100::/64", "2001::/32", "2001:2::/48", "2001:10::/28", "2001:20::/28",
            "2001:db8::/32", "3fff::/20", "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8")
            .map(IpAddresses.Cidr::parse).toList();

    private static boolean isPublic(final InetAddress address) {
        final InetAddress embedded = embeddedIpv4(address);
        if (embedded != null) {
            return isPublic(embedded);
        }
        for (final IpAddresses.Cidr range : SPECIAL_PURPOSE) {
            if (range.contains(address)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The IPv4 address an IPv6 address carries and leads to, or null: IPv4-mapped ({@code ::ffff:0:0/96}), NAT64's
     * well-known prefix ({@code 64:ff9b::/96}), and 6to4 ({@code 2002::/16}). Judged by that address, so NAT64, how an
     * IPv6-only network reaches IPv4, still reaches public endpoints and not private ones.
     */
    private static InetAddress embeddedIpv4(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return null;
        }
        final int offset;
        if (startsWith(bytes, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff)
                || startsWith(bytes, 0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)) {
            offset = 12;
        } else if (startsWith(bytes, 0x20, 0x02)) {
            offset = 2;
        } else {
            return null;
        }
        try {
            return InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, offset, offset + 4));
        } catch (final UnknownHostException cannotHappen) {
            return null;
        }
    }

    private static boolean startsWith(final byte[] bytes, final int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

}
