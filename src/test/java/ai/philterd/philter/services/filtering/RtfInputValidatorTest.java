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

import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.testutil.RtfDocuments;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtfInputValidatorTest {

    private static final RtfInputValidator.Limits LIMITS = new RtfInputValidator.Limits(1000, 5);

    @Test
    void aReadableDocumentWithinTheLimitsPasses() {
        assertDoesNotThrow(() -> RtfInputValidator.validate(RtfDocuments.rtf("Hello."), LIMITS));
        assertDoesNotThrow(() -> RtfInputValidator.validate(RtfDocuments.nested(4, "x"), LIMITS));
    }

    @Test
    void aDocumentOverTheSizeLimitIsRefusedFirst() {
        final byte[] large = RtfDocuments.rtf("x".repeat(1000));
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> RtfInputValidator.validate(large, LIMITS));
        assertTrue(refused.getMessage().contains("RTF_MAX_BYTES"), refused.getMessage());
        // Before the signature: a large body that is not RTF is refused for its size.
        assertThrows(PayloadTooLargeException.class,
                () -> RtfInputValidator.validate("x".repeat(2000).getBytes(StandardCharsets.US_ASCII), LIMITS));
    }

    @Test
    void aBodyThatIsNotRtfIsRefused() {
        for (final String body : new String[]{"", "{\\rt", "Hello.", " {\\rtf1 }", "﻿{\\rtf1 }"}) {
            assertThrows(UnsupportedMediaTypeException.class,
                    () -> RtfInputValidator.validate(body.getBytes(StandardCharsets.UTF_8), LIMITS), body);
        }
    }

    @Test
    void aDocumentNestedTooDeepIsRefused() {
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> RtfInputValidator.validate(RtfDocuments.nested(5, "x"), LIMITS));
        assertTrue(refused.getMessage().contains("RTF_MAX_NESTING_DEPTH"), refused.getMessage());
    }

    @Test
    void nestingIsMeasuredAsPhileasReadsIt() {
        // The document's group and three more.
        assertEquals(4, RtfInputValidator.nestingDepth(RtfDocuments.nested(3, "x")));
        // Escaped braces are text.
        assertEquals(1, RtfInputValidator.nestingDepth(RtfDocuments.bytes("{\\rtf1 \\{\\{\\{ text \\}\\}\\}}")));
        // The bytes after \binN are data, even when they are braces.
        assertEquals(2, RtfInputValidator.nestingDepth(RtfDocuments.bytes("{\\rtf1 {\\bin6 {{{{{{}}")));
        assertEquals(2, RtfInputValidator.nestingDepth(RtfDocuments.bytes("{\\rtf1 {\\bin6{{{{{{}}")));
        // A hex escape's digits are not braces, and a word that only starts with bin is not \bin.
        assertEquals(2, RtfInputValidator.nestingDepth(RtfDocuments.bytes("{\\rtf1 \\'7b{\\binx3 x}}")));
        // Reading stops at the document's closing brace.
        assertEquals(1, RtfInputValidator.nestingDepth(RtfDocuments.bytes("{\\rtf1 x}{{{{{{")));
    }

    @Test
    void aMalformedDocumentIsRefusedWithoutItsText() {
        for (final String body : new String[]{"{\\rtf1 {\\b SSN 123-45-6789", "{\\rtf1 \\'zz}", "{\\rtf1 \\bin99 x}"}) {
            final BadRequestException refused = assertThrows(BadRequestException.class,
                    () -> RtfInputValidator.validate(RtfDocuments.bytes(body), LIMITS), body);
            assertTrue(refused.getMessage().startsWith("The RTF document cannot be read"), refused.getMessage());
            assertFalse(refused.getMessage().contains("123-45-6789"), refused.getMessage());
        }
    }

    @Test
    void zeroOrLessSetsNoLimit() {
        final RtfInputValidator.Limits none = new RtfInputValidator.Limits(0, -1);
        assertDoesNotThrow(() -> RtfInputValidator.validate(RtfDocuments.nested(500, "x".repeat(5000)), none));
    }

    @Test
    void declaredContentTypes() {
        assertTrue(RtfInputValidator.isDeclared("application/rtf"));
        assertTrue(RtfInputValidator.isDeclared("TEXT/RTF; charset=US-ASCII"));
        assertFalse(RtfInputValidator.isDeclared("text/plain"));
        assertFalse(RtfInputValidator.isDeclared(null));
    }

}
