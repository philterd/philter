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
import ai.philterd.phileas.services.ooxml.OoxmlDocumentType;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.testutil.OfficeDocuments;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeInputValidatorTest {

    private static final byte[] DOCX = OfficeDocuments.docx("Hello");
    private static final byte[] XLSX = OfficeDocuments.xlsx("Hello");

    @Test
    void acceptsADocumentOfTheDeclaredType() {
        assertDoesNotThrow(() -> OfficeInputValidator.validate(DOCX, OoxmlDocumentType.DOCX));
        assertDoesNotThrow(() -> OfficeInputValidator.validate(XLSX, OoxmlDocumentType.XLSX));
    }

    @Test
    void refusesADocumentOfTheOtherType() {
        final var e = assertThrows(UnsupportedMediaTypeException.class,
                () -> OfficeInputValidator.validate(XLSX, OoxmlDocumentType.DOCX));
        assertTrue(e.getMessage().contains("an Excel workbook"), e.getMessage());
    }

    @Test
    void refusesAZipThatIsNotAnOfficeDocument() {
        final var e = assertThrows(UnsupportedMediaTypeException.class,
                () -> OfficeInputValidator.validate(OfficeDocuments.plainZip(), OoxmlDocumentType.XLSX));
        assertTrue(e.getMessage().contains("not a Word document or Excel workbook"), e.getMessage());
    }

    @Test
    void refusesABodyThatIsNotAZip() {
        assertThrows(BadRequestException.class,
                () -> OfficeInputValidator.validate("not a zip".getBytes(), OoxmlDocumentType.DOCX));
    }

    @Test
    void refusesADocumentThatDecompressesPastTheLimit() {
        final byte[] large = OfficeDocuments.docx(words(200_000));

        final var e = assertThrows(PayloadTooLargeException.class,
                () -> OfficeInputValidator.validate(large, OoxmlDocumentType.DOCX, 500_000, 0));
        assertTrue(e.getMessage().contains("OFFICE_MAX_UNCOMPRESSED_BYTES"), e.getMessage());
        assertDoesNotThrow(() -> OfficeInputValidator.validate(large, OoxmlDocumentType.DOCX, 0, 0));
    }

    @Test
    void refusesADocumentPoiTreatsAsAZipBombAsTooLargeRatherThanNotOffice() {
        // A megabyte of one letter compresses more than POI's 100:1 limit allows.
        final byte[] bomb = OfficeDocuments.docx("a".repeat(1_000_000));

        final var e = assertThrows(PayloadTooLargeException.class,
                () -> OfficeInputValidator.validate(bomb, OoxmlDocumentType.DOCX, 0, 0));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
    }

    // Text that compresses about as well as prose.
    private static String words(final int count) {
        final java.util.Random random = new java.util.Random(7);
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            for (int j = 2 + random.nextInt(6); j > 0; j--) {
                text.append((char) ('a' + random.nextInt(26)));
            }
            text.append(' ');
        }
        return text.toString();
    }

    @Test
    void refusesADocumentWithTooManyEntries() {
        final Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            entries.put("part" + i + ".xml", "<x/>");
        }
        final var e = assertThrows(PayloadTooLargeException.class,
                () -> OfficeInputValidator.validate(OfficeDocuments.zip(entries), OoxmlDocumentType.DOCX, 0, 10));
        assertTrue(e.getMessage().contains("OFFICE_MAX_ENTRIES"), e.getMessage());
    }

    @Test
    void readsTheDeclaredTypeFromAContentTypeHeader() {
        assertEquals(OoxmlDocumentType.DOCX, OfficeInputValidator.declaredType(MimeType.APPLICATION_DOCX.getValue()));
        assertEquals(OoxmlDocumentType.XLSX, OfficeInputValidator.declaredType(
                "Application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=binary"));
        assertNull(OfficeInputValidator.declaredType("application/pdf"));
        assertNull(OfficeInputValidator.declaredType((String) null));
        assertEquals(OoxmlDocumentType.XLSX, OfficeInputValidator.declaredType(MimeType.APPLICATION_XLSX));
        assertNull(OfficeInputValidator.declaredType(MimeType.APPLICATION_PDF));
    }

}
