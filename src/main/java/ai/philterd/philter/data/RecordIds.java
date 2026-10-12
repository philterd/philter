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
package ai.philterd.philter.data;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** The form of a record id: 24 hexadecimal characters, the same for every storage implementation. */
public final class RecordIds {

    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private RecordIds() {
    }

    /**
     * A new id of the record id form, for an id that is not a stored record's: the current time in seconds
     * followed by random bytes, so ids made later sort later, as stored record ids do.
     */
    public static String newId() {
        final byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        final int seconds = (int) (System.currentTimeMillis() / 1000);
        bytes[0] = (byte) (seconds >>> 24);
        bytes[1] = (byte) (seconds >>> 16);
        bytes[2] = (byte) (seconds >>> 8);
        bytes[3] = (byte) seconds;
        return HexFormat.of().formatHex(bytes);
    }

    /** Whether the value has the form of a record id. It says nothing about whether the record exists. */
    public static boolean isValid(final String id) {
        return id != null && ID.matcher(id).matches();
    }

}
