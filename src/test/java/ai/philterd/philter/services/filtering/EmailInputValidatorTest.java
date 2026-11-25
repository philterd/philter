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
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.services.filtering.EmailInputValidator.Format;
import ai.philterd.philter.services.filtering.EmailInputValidator.Limits;
import ai.philterd.philter.testutil.EmailMessages;
import ai.philterd.philter.testutil.OfficeDocuments;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailInputValidatorTest {

    private static final Limits NONE = new Limits(0, 0, 0, 0);

    @Test
    void readsTheDeclaredFormat() {
        assertEquals(Format.EML, EmailInputValidator.declaredFormat("message/rfc822"));
        assertEquals(Format.MSG, EmailInputValidator.declaredFormat("Application/Vnd.MS-Outlook; charset=binary"));
        assertEquals(Format.MSG, EmailInputValidator.declaredFormat(MimeType.APPLICATION_VND_MS_OUTLOOK));
        assertNull(EmailInputValidator.declaredFormat("text/plain"));
        assertNull(EmailInputValidator.declaredFormat((String) null));
    }

    @Test
    void acceptsMessagesOfTheDeclaredFormat() throws Exception {
        assertDoesNotThrow(() -> EmailInputValidator.validate(EmailMessages.eml("Hello", "Hi."), Format.EML, NONE));
        assertDoesNotThrow(() -> EmailInputValidator.validate(EmailMessages.msg("Hello", "Hi.", 10), Format.MSG, NONE));
    }

    @Test
    void refusesALegacyWordDocumentDeclaredAsOutlook() throws Exception {
        final UnsupportedMediaTypeException refused = assertThrows(UnsupportedMediaTypeException.class,
                () -> EmailInputValidator.validate(EmailMessages.legacyWordDocument(), Format.MSG, NONE));
        assertTrue(refused.getMessage().contains("not an Outlook message"), refused.getMessage());
    }

    @Test
    void refusesABodyThatIsNotAMimeMessage() {
        assertThrows(UnsupportedMediaTypeException.class, () -> EmailInputValidator.validate(
                "Just some notes, not a message.\nSecond line.".getBytes(StandardCharsets.UTF_8), Format.EML, NONE));
        assertThrows(UnsupportedMediaTypeException.class, () -> EmailInputValidator.validate(
                "X-Custom: only a header nobody sends alone\n\nBody".getBytes(StandardCharsets.UTF_8), Format.EML, NONE));
        assertThrows(UnsupportedMediaTypeException.class, () -> EmailInputValidator.validate(new byte[0], Format.EML, NONE));
    }

    @Test
    void refusesAnOutlookMessageDeclaredAsMime() throws Exception {
        assertThrows(UnsupportedMediaTypeException.class,
                () -> EmailInputValidator.validate(EmailMessages.msg("Hello", "Hi."), Format.EML, NONE));
        assertThrows(UnsupportedMediaTypeException.class,
                () -> EmailInputValidator.validate(OfficeDocuments.docx("Hi."), Format.MSG, NONE));
    }

    @Test
    void refusesAMessageLargerThanTheLimit() throws Exception {
        final byte[] eml = EmailMessages.eml("Hello", "Hi.");
        assertDoesNotThrow(() -> EmailInputValidator.validate(eml, Format.EML, new Limits(eml.length, 0, 0, 0)));
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(eml, Format.EML, new Limits(eml.length - 1, 0, 0, 0)));
        assertTrue(refused.getMessage().contains("EMAIL_MAX_BYTES"), refused.getMessage());
    }

    @Test
    void refusesAnAttachmentLargerThanTheLimit() throws Exception {
        final byte[] eml = EmailMessages.emlWithAttachment("Hi.", "data.bin", "application/octet-stream", new byte[1000]);
        assertDoesNotThrow(() -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 1000, 0, 0)));
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 999, 0, 0)));
        assertTrue(refused.getMessage().contains("EMAIL_MAX_ATTACHMENT_BYTES"), refused.getMessage());

        final byte[] msg = EmailMessages.msg("Hello", "Hi.", 1000);
        assertDoesNotThrow(() -> EmailInputValidator.validate(msg, Format.MSG, new Limits(0, 1000, 0, 0)));
        assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(msg, Format.MSG, new Limits(0, 999, 0, 0)));
    }

    @Test
    void refusesMoreAttachmentsThanTheLimit() throws Exception {
        final byte[] eml = EmailMessages.emlWithAttachments(3);
        assertDoesNotThrow(() -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 0, 3, 0)));
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 0, 2, 0)));
        assertTrue(refused.getMessage().contains("EMAIL_MAX_ATTACHMENTS"), refused.getMessage());

        final byte[] msg = EmailMessages.msg("Hello", "Hi.", 1, 1, 1);
        assertDoesNotThrow(() -> EmailInputValidator.validate(msg, Format.MSG, new Limits(0, 0, 3, 0)));
        assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(msg, Format.MSG, new Limits(0, 0, 2, 0)));
    }

    @Test
    void refusesMessagesNestedDeeperThanTheLimit() throws Exception {
        final byte[] eml = EmailMessages.nested(3, "Innermost text.");
        assertDoesNotThrow(() -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 0, 0, 3)));
        final PayloadTooLargeException refused = assertThrows(PayloadTooLargeException.class,
                () -> EmailInputValidator.validate(eml, Format.EML, new Limits(0, 0, 0, 2)));
        assertTrue(refused.getMessage().contains("EMAIL_MAX_NESTING_DEPTH"), refused.getMessage());
    }

}
