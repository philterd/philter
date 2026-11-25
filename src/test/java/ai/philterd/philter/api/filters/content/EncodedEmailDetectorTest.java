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

import ai.philterd.philter.testutil.EmailMessages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodedEmailDetectorTest {

    private static final String SSN_TEXT = "The patient's SSN is 123-45-6789.";

    private static String detect(final String text) {
        return EncodedEmailDetector.detect(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String detect(final byte[] bytes) {
        final int length = Math.min(bytes.length, EncodedEmailDetector.SCAN_LIMIT);
        return EncodedEmailDetector.detect(java.util.Arrays.copyOf(bytes, length));
    }

    @Test
    void recognizesBase64QuotedPrintableAndMultipartMessages() throws Exception {
        assertEquals("Content-Transfer-Encoding: base64", detect(EmailMessages.emlEncoded("base64", SSN_TEXT)));
        assertEquals("Content-Transfer-Encoding: quoted-printable",
                detect(EmailMessages.emlEncoded("quoted-printable", SSN_TEXT)));
        final String multipart = detect(EmailMessages.emlWithAttachment(SSN_TEXT, "notes.txt", "text/plain",
                SSN_TEXT.getBytes(StandardCharsets.UTF_8)));
        assertTrue(multipart != null && multipart.startsWith("Content-Type: multipart/mixed"), multipart);
    }

    @Test
    void readsFoldedHeadersCaseAndParameters() {
        assertEquals("Content-Transfer-Encoding: base64", detect(
                "From: sender@example.com\r\ncontent-transfer-encoding:\r\n  BASE64 (encoded)\r\n\r\nVGhlIFNTTiBpcyAxMjM=\r\n"));
        assertEquals("Content-Type: multipart/alternative", detect(
                "Subject: Hello\nContent-Type: Multipart/Alternative;\n\tboundary=\"b1\"\n\n--b1\n"));
    }

    @Test
    void followsAForwardedMessageIntoItsHeaders() {
        assertEquals("Content-Transfer-Encoding: base64", detect("From: sender@example.com\nContent-Type: message/rfc822\n\n"
                + "From: other@example.com\nContent-Transfer-Encoding: base64\n\nVGhlIFNTTiBpcyAxMjM=\n"));
    }

    @Test
    void skipsALeadingMboxSeparatorLine() {
        assertEquals("Content-Transfer-Encoding: base64", detect("From sender@example.com Mon Nov 23 10:00:00 2026\n"
                + "From: sender@example.com\nContent-Transfer-Encoding: base64\n\nVGhlIFNTTiBpcyAxMjM=\n"));
        assertNull(detect("From the desk of the director\nPlease see the attached notes.\n"));
    }

    @Test
    void acceptsAnUnencodedEmail() throws Exception {
        assertNull(detect(EmailMessages.eml("Hello", SSN_TEXT)));
        assertNull(detect(EmailMessages.emlEncoded("7bit", SSN_TEXT)));
    }

    @Test
    void acceptsTextThatOnlyLooksLikeHeaders() {
        assertNull(detect("From: the desk of the director\nPlease see the attached notes. " + SSN_TEXT));
        assertNull(detect("Subject: quarterly review\n\n" + SSN_TEXT));
        assertNull(detect("Note: call back\nRe: billing\n\nThe base64 value was garbled."));
    }

    @Test
    void acceptsEncodingNamedOutsideAHeaderBlock() {
        assertNull(detect("The message had Content-Transfer-Encoding: base64 and could not be read.\n" + SSN_TEXT));
        assertNull(detect(SSN_TEXT + "\nContent-Transfer-Encoding: base64\n\nVGhlIFNTTiBpcyAxMjM=\n"));
        assertNull(detect("\nContent-Transfer-Encoding: base64\n\nVGhlIFNTTiBpcyAxMjM=\n"));
    }

    @Test
    void acceptsAHeaderBlockThatDoesNotEndWithinTheScan() {
        assertNull(detect("Content-Transfer-Encoding: base64\nX-Note: unfinished"));
    }

    @Test
    void scansABoundedPrefixQuickly() {
        // The worst case for the scan: header lines all the way to the limit, with no blank line.
        final StringBuilder headers = new StringBuilder();
        while (headers.length() < EncodedEmailDetector.SCAN_LIMIT) {
            headers.append("X-Trace-").append(headers.length()).append(": a value of some length\r\n");
        }
        final long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertNull(detect(headers.toString()));
        }
        final long millis = (System.nanoTime() - started) / 1_000_000 / 20;
        assertTrue(millis < 50, "one scan took " + millis + " ms");
    }

}
