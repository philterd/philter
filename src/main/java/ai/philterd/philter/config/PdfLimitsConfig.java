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

import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * Checks {@code PDF_MAX_PAGES} and {@code PDF_MAX_PIXELS_PER_PAGE} when Philter starts. Phileas reads them only when
 * the first PDF is redacted, so a value that is not a number would otherwise fail every PDF request.
 */
@Configuration
public class PdfLimitsConfig {

    public PdfLimitsConfig() {
        validate(System::getenv);
    }

    static void validate(final Function<String, String> environment) {
        check(environment, "PDF_MAX_PAGES", Integer::parseInt);
        check(environment, "PDF_MAX_PIXELS_PER_PAGE", Long::parseLong);
    }

    private static void check(final Function<String, String> environment, final String name,
                              final Function<String, Number> parser) {
        final String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            parser.apply(value.strip());
        } catch (final NumberFormatException e) {
            throw new IllegalStateException(name + " must be a whole number; 0 or less sets no limit.");
        }
    }

}
