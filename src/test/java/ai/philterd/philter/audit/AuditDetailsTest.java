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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditDetailsTest {

    @Test
    void ordinaryValuesAreWrittenAsIs() {
        assertEquals("LIT-2026-001", AuditDetails.quote("LIT-2026-001"));
        assertEquals("case 12 (appeal)", AuditDetails.quote("case 12 (appeal)"));
        assertEquals("jordan@example.com", AuditDetails.quote("jordan@example.com"));
        assertEquals("null", AuditDetails.quote(null));
    }

    @Test
    void aValueThatCouldAddFieldsIsQuoted() {
        assertEquals("\"x, owner: admin\"", AuditDetails.quote("x, owner: admin"));
        assertEquals("\"a]\"", AuditDetails.quote("a]"));
        assertEquals("\"say \\\"hi\\\"\"", AuditDetails.quote("say \"hi\""));
        assertEquals("\"C:\\\\path\"", AuditDetails.quote("C:\\path"));
        assertEquals("\"\"", AuditDetails.quote(""));
        assertEquals("\" padded\"", AuditDetails.quote(" padded"));
    }

    @Test
    void controlAndInvisibleCharactersAreEscaped() {
        assertEquals("\"a\\nreason: forged\"", AuditDetails.quote("a\nreason: forged"));
        // A right-to-left override could make the text read differently from what it is.
        assertEquals("\"ab\\u202ecd\"", AuditDetails.quote("ab\u202Ecd"));
        assertEquals("\"line\\u2028break\"", AuditDetails.quote("line\u2028break"));
        // A tag character, invisible and outside the Basic Multilingual Plane.
        assertEquals("\"a\\udb40\\udc41b\"", AuditDetails.quote("a\uDB40\uDC41b"));
        // A lone surrogate is not a character at all.
        assertEquals("\"a\\ud800\"", AuditDetails.quote("a\uD800"));
        // Visible characters outside the Basic Multilingual Plane are kept.
        assertEquals("case-\uD83D\uDCC1", AuditDetails.quote("case-\uD83D\uDCC1"));
    }

    @Test
    void listsQuoteEachValue() {
        assertEquals("[case-1, \"case-2, released: true\"]", AuditDetails.quoteAll(List.of("case-1", "case-2, released: true")));
        assertEquals("[]", AuditDetails.quoteAll(List.of()));
    }

}
