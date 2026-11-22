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

import ai.philterd.philter.services.filtering.OfficeInputValidator;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/** Checks {@code OFFICE_MAX_UNCOMPRESSED_BYTES} and {@code OFFICE_MAX_ENTRIES} when Philter starts. */
@Configuration
public class OfficeLimitsConfig {

    public OfficeLimitsConfig() {
        validate(System::getenv);
        // Bounds every entry POI reads, including those of Word and Excel documents embedded in the one submitted,
        // which OfficeInputValidator does not open.
        final long max = OfficeInputValidator.maxUncompressedBytes();
        if (max > 0) {
            ZipSecureFile.setMaxEntrySize(max);
        }
    }

    static void validate(final Function<String, String> environment) {
        PdfLimitsConfig.check(environment, "OFFICE_MAX_UNCOMPRESSED_BYTES", Long::parseLong);
        PdfLimitsConfig.check(environment, "OFFICE_MAX_ENTRIES", Integer::parseInt);
    }

}
