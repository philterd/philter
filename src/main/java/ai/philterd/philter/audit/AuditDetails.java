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
package ai.philterd.philter.audit;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Writes a caller-supplied value into an audit event's {@code details}, which are {@code key: value} pairs joined
 * by {@code , }. A name, reference, or reason can contain those separators, so written as is it could add fields of
 * its own, such as {@code x, owner: admin}. A value that could be misread is written as a JSON string instead.
 */
public final class AuditDetails {

    private AuditDetails() {
    }

    /**
     * The value as is when it cannot be mistaken for more than one value, otherwise in double quotes with
     * {@code \}, {@code "}, control characters, and invisible formatting characters escaped as in JSON.
     */
    public static String quote(final String value) {
        if (value == null) {
            return "null";
        }
        return needsQuotes(value) ? escape(value) : value;
    }

    /** Each value quoted as {@link #quote} does, joined by {@code , } in square brackets. */
    public static String quoteAll(final Collection<String> values) {
        return values.stream().map(AuditDetails::quote).collect(Collectors.joining(", ", "[", "]"));
    }

    private static boolean needsQuotes(final String value) {
        if (value.isEmpty() || Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))) {
            return true;
        }
        return value.codePoints().anyMatch(c -> c == ',' || c == ':' || c == '"' || c == '\\' || c == '['
                || c == ']' || unprintable(c));
    }

    /** Checked by code point, so an invisible character outside the Basic Multilingual Plane is caught too. */
    private static boolean unprintable(final int codePoint) {
        final int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    private static String escape(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(c -> {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (unprintable(c)) {
                        // As JSON writes it: one escape per UTF-16 unit, a surrogate pair for a supplementary character.
                        for (final char unit : Character.toChars(c)) {
                            out.append(String.format("\\u%04x", (int) unit));
                        }
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.append('"').toString();
    }

}
