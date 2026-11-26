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
package ai.philterd.philter.testutil;

import ai.philterd.phileas.services.rtf.RtfTextExtractor;

import java.nio.charset.StandardCharsets;

/** RTF documents generated for tests. None comes from a real document. */
public final class RtfDocuments {

    private static final String HEAD = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}";

    private RtfDocuments() {
    }

    /** A document whose body is the given RTF, such as {@code "Hello.\\par "}. */
    public static String doc(final String body) {
        return HEAD + "\\pard\\f0\\fs20 " + body + "}";
    }

    /** A document with one paragraph of plain text. */
    public static byte[] rtf(final String text) {
        return bytes(doc(text + "\\par "));
    }

    /** A document whose paragraph has a comment by {@code author} holding {@code comment}. */
    public static byte[] commented(final String text, final String comment, final String author) {
        return bytes(doc(text + "{\\*\\atrfstart 1}{\\*\\atrfend 1}{\\*\\atnid EA}{\\*\\atnauthor " + author
                + "}\\chatn {\\*\\annotation{\\*\\atnref 1}\\pard " + comment + "}.\\par "));
    }

    /** A document whose body is nested {@code depth} groups deep inside the document's own group. */
    public static byte[] nested(final int depth, final String text) {
        return bytes(HEAD + "{".repeat(depth) + text + "}".repeat(depth) + "}");
    }

    public static byte[] bytes(final String rtf) {
        return rtf.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The visible text of an RTF document, as Phileas reads it, so escaped replacements read as written. */
    public static String text(final byte[] rtf) {
        return RtfTextExtractor.extract(rtf);
    }

}
