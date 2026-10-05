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

import java.net.InetAddress;
import java.util.regex.Pattern;

/** IP address literals and CIDR ranges, parsed without DNS. */
public final class IpAddresses {

    /** A dotted-quad IPv4 address; the shorter forms InetAddress also accepts, such as "10", are not. */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private IpAddresses() {
    }

    /**
     * The address a literal names, or {@code null} if the value is not a dotted-quad IPv4 or an IPv6
     * literal. Never looks anything up in DNS.
     */
    public static InetAddress parseLiteral(final String value) {

        if (value == null || value.isEmpty() || !value.contains(":") && !IPV4.matcher(value).matches()) {
            return null;
        }

        try {
            return InetAddress.ofLiteral(value);
        } catch (final IllegalArgumentException notALiteral) {
            return null;
        }

    }

    /** An address and prefix length, matched by comparing the leading bits. */
    public record Cidr(byte[] network, int prefixBits) {

        /** The range an entry names: an address, or an address and prefix length. {@code null} if neither. */
        public static Cidr parse(final String entry) {

            final int slash = entry.indexOf('/');
            final InetAddress address = parseLiteral(slash < 0 ? entry : entry.substring(0, slash));

            if (address == null) {
                return null;
            }

            final byte[] bytes = address.getAddress();
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (final NumberFormatException ex) {
                    return null;
                }
                if (prefix < 0 || prefix > bytes.length * 8) {
                    return null;
                }
            }

            return new Cidr(bytes, prefix);

        }

        public boolean contains(final InetAddress candidate) {

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
