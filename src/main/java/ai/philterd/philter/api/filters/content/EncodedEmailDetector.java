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
package ai.philterd.philter.api.filters.content;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognizes a text body that is an email message with encoded or multipart content: one that starts with a
 * header block declaring {@code Content-Transfer-Encoding: base64} or {@code quoted-printable}, or
 * {@code Content-Type: multipart/*}. Its encoded content is not text a filter can match, so redacting it as
 * text would return encoded values unchanged. Email without such content redacts correctly as text and is not
 * recognized.
 *
 * <p>A header block is every line from the start of the body to the first blank line, each a header field
 * ({@code Name: value}) or a folded continuation of one. Anything else before the blank line means the body is
 * not an email message. A {@code message/rfc822} body is followed into the message it holds. A leading mbox
 * separator line ({@code From sender@example.com Mon Nov 23 10:00:00 2026}), which exported mail can begin
 * with, is skipped.
 */
final class EncodedEmailDetector {

    /** How much of the body is read. Long Received and DKIM chains fit well within it. */
    static final int SCAN_LIMIT = 256 * 1024;

    // A message/rfc822 body holding another, and so on.
    private static final int MAX_DEPTH = 10;

    // A header name is printable ASCII other than the colon.
    private static final Pattern HEADER = Pattern.compile("[!-9;-~]+:.*");

    private EncodedEmailDetector() {
    }

    /**
     * What makes the body encoded, such as {@code Content-Transfer-Encoding: base64}, or null when it is not an
     * encoded or multipart email message.
     * @param leading The start of the body, up to {@link #SCAN_LIMIT} bytes.
     */
    static String detect(final byte[] leading) {
        // Header fields are ASCII; ISO-8859-1 keeps one character per byte for anything else.
        String text = new String(leading, StandardCharsets.ISO_8859_1);
        if (text.startsWith("ï»¿")) {
            text = text.substring(3);
        }
        int start = 0;
        if (text.startsWith("From ")) {
            final int end = text.indexOf('\n');
            if (end < 0) {
                return null;
            }
            start = end + 1;
        }
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            final Block block = headerBlock(text, start);
            if (block == null) {
                return null;
            }
            if (block.encoding != null) {
                return "Content-Transfer-Encoding: " + block.encoding;
            }
            if (block.type != null && block.type.startsWith("multipart/")) {
                return "Content-Type: " + block.type;
            }
            if (!"message/rfc822".equals(block.type)) {
                return null;
            }
            start = block.end;
        }
        return null;
    }

    private record Block(String encoding, String type, int end) {
    }

    // The header block starting at start, or null when there is none ending within the text.
    private static Block headerBlock(final String text, final int start) {

        String encoding = null;
        String type = null;
        String name = null;
        final StringBuilder value = new StringBuilder();
        boolean fields = false;

        int position = start;
        while (position < text.length()) {

            int end = text.indexOf('\n', position);
            if (end < 0) {
                // The block does not end within what was read.
                return null;
            }
            final String line = text.substring(position, end > position && text.charAt(end - 1) == '\r' ? end - 1 : end);
            position = end + 1;

            if (line.isEmpty()) {
                if (!fields) {
                    return null;
                }
                if (name != null) {
                    encoding = encodingOf(name, value, encoding);
                    type = typeOf(name, value, type);
                }
                return new Block(encoding, type, position);
            }

            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                value.append(' ').append(line.strip());
                continue;
            }

            if (!HEADER.matcher(line).matches()) {
                return null;
            }

            if (name != null) {
                encoding = encodingOf(name, value, encoding);
                type = typeOf(name, value, type);
            }
            final int colon = line.indexOf(':');
            name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            value.setLength(0);
            value.append(line.substring(colon + 1).strip());
            fields = true;

        }

        return null;

    }

    private static String encodingOf(final String name, final CharSequence value, final String current) {
        if (!"content-transfer-encoding".equals(name)) {
            return current;
        }
        final String encoding = bare(value);
        return "base64".equals(encoding) || "quoted-printable".equals(encoding) ? encoding : current;
    }

    private static String typeOf(final String name, final CharSequence value, final String current) {
        return "content-type".equals(name) ? bare(value) : current;
    }

    // The value without parameters or a comment, in lower case.
    private static String bare(final CharSequence value) {
        String bare = value.toString();
        final int semicolon = bare.indexOf(';');
        if (semicolon >= 0) {
            bare = bare.substring(0, semicolon);
        }
        final int comment = bare.indexOf('(');
        if (comment >= 0) {
            bare = bare.substring(0, comment);
        }
        return bare.strip().toLowerCase(Locale.ROOT);
    }

}
