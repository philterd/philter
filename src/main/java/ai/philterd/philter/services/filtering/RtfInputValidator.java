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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.services.rtf.RtfFormatException;
import ai.philterd.phileas.services.rtf.RtfTextExtractor;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.utils.EnvUtils;

import java.util.Set;

/**
 * Checks an RTF body before it is redacted: that it stays within {@code RTF_MAX_BYTES} and
 * {@code RTF_MAX_NESTING_DEPTH}, that it is RTF, and that Phileas can read it. A document Phileas would refuse is
 * refused here, so a queued one is refused when it is submitted rather than failing when it runs.
 */
public final class RtfInputValidator {

    /** The content types Philter accepts as RTF. {@code text/rtf} is what some clients send. */
    public static final Set<String> CONTENT_TYPES = Set.of("application/rtf", "text/rtf");

    record Limits(long maxBytes, int maxNestingDepth) {
    }

    static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    static final int DEFAULT_MAX_NESTING_DEPTH = 100;

    private static final Limits LIMITS = new Limits(
            EnvUtils.getLong("RTF_MAX_BYTES", DEFAULT_MAX_BYTES),
            EnvUtils.getInt("RTF_MAX_NESTING_DEPTH", DEFAULT_MAX_NESTING_DEPTH));

    private static final byte[] SIGNATURE = {'{', '\\', 'r', 't', 'f'};

    private RtfInputValidator() {
    }

    /** Whether a MIME type is RTF. */
    public static boolean isRtf(final MimeType mimeType) {
        return mimeType == MimeType.APPLICATION_RTF;
    }

    /** Whether a {@code Content-Type} header declares RTF, ignoring parameters. */
    public static boolean isDeclared(final String contentType) {
        return contentType != null && CONTENT_TYPES.contains(contentType.split(";", 2)[0].strip().toLowerCase());
    }

    public static void validate(final byte[] input) {
        validate(input, LIMITS);
    }

    /** As {@link #validate(byte[])} with the given limits; 0 or less sets no limit. */
    static void validate(final byte[] input, final Limits limits) {

        if (limits.maxBytes() > 0 && input.length > limits.maxBytes()) {
            throw new PayloadTooLargeException("The RTF document is larger than " + limits.maxBytes()
                    + " bytes, the most RTF_MAX_BYTES allows.");
        }

        if (!startsWithSignature(input)) {
            throw new UnsupportedMediaTypeException("The request declares RTF but the body does not start with {\\rtf.");
        }

        if (limits.maxNestingDepth() > 0 && nestingDepth(input) > limits.maxNestingDepth()) {
            throw new PayloadTooLargeException("The RTF document nests groups more than " + limits.maxNestingDepth()
                    + " deep, the most RTF_MAX_NESTING_DEPTH allows.");
        }

        try {
            RtfTextExtractor.extract(input);
        } catch (final RtfFormatException e) {
            throw new BadRequestException("The RTF document cannot be read: " + e.getMessage());
        }

    }

    private static boolean startsWithSignature(final byte[] input) {
        if (input.length < SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (input[i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The deepest group nesting in the document, read as Phileas reads it: an escaped brace is text, and the bytes
     * after {@code \binN} are data, so braces in either do not count. Reading stops at the document's closing brace.
     * A malformed document is measured as far as it goes; Phileas refuses it afterwards.
     */
    static int nestingDepth(final byte[] input) {

        int depth = 0;
        int deepest = 0;
        int i = 0;

        while (i < input.length) {
            final byte b = input[i];
            if (b == '{') {
                deepest = Math.max(deepest, ++depth);
                i++;
            } else if (b == '}') {
                i++;
                if (--depth == 0) {
                    break;
                }
            } else if (b == '\\') {
                i = afterControl(input, i);
            } else {
                i++;
            }
        }

        return deepest;

    }

    // Where the control word, control symbol, or hex escape at the backslash at i ends, past any \binN data.
    private static int afterControl(final byte[] input, final int i) {

        if (i + 1 >= input.length) {
            return input.length;
        }
        if (!isLetter(input[i + 1])) {
            // A control symbol, such as an escaped brace, or the start of a hex escape, whose digits are not braces.
            return i + 2;
        }

        int j = i + 1;
        while (j < input.length && isLetter(input[j])) {
            j++;
        }
        final boolean bin = j - (i + 1) == 3 && input[i + 1] == 'b' && input[i + 2] == 'i' && input[i + 3] == 'n';

        long parameter = 0;
        boolean digits = false;
        boolean negative = false;
        if (j < input.length && input[j] == '-') {
            negative = true;
            j++;
        }
        while (j < input.length && input[j] >= '0' && input[j] <= '9') {
            parameter = Math.min(parameter * 10 + (input[j] - '0'), Integer.MAX_VALUE);
            digits = true;
            j++;
        }
        if (j < input.length && input[j] == ' ') {
            j++;
        }

        if (bin && digits && !negative && parameter > 0) {
            return (int) Math.min((long) j + parameter, input.length);
        }
        return j;

    }

    private static boolean isLetter(final byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
    }

}
