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
package ai.philterd.philter.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfLimitsConfigTest {

    @Test
    void acceptsUnsetAndWholeNumbers() {
        assertDoesNotThrow(() -> PdfLimitsConfig.validate(Map.<String, String>of()::get));
        assertDoesNotThrow(() -> PdfLimitsConfig.validate(Map.of("PDF_MAX_PAGES", " 500 ",
                "PDF_MAX_PIXELS_PER_PAGE", "0")::get));
    }

    @Test
    void refusesAValueThatIsNotAWholeNumber() {
        assertThrows(IllegalStateException.class, () -> PdfLimitsConfig.validate(Map.of("PDF_MAX_PAGES", "2k")::get));
        assertThrows(IllegalStateException.class,
                () -> PdfLimitsConfig.validate(Map.of("PDF_MAX_PIXELS_PER_PAGE", "5e7")::get));
        assertThrows(IllegalStateException.class,
                () -> PdfLimitsConfig.validate(Map.of("PDF_MAX_STREAM_BYTES", "256MB")::get));
        assertThrows(IllegalStateException.class, () -> PdfLimitsConfig.validate(Map.of("PDF_MAX_CONCURRENT", "two")::get));
        // Pages must fit in an int, as Phileas parses them.
        assertThrows(IllegalStateException.class,
                () -> PdfLimitsConfig.validate(Map.of("PDF_MAX_PAGES", "3000000000")::get));
    }

}
