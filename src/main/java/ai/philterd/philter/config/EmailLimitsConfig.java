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

/** Checks the {@code EMAIL_MAX_*} limits when Philter starts. */
@Configuration
public class EmailLimitsConfig {

    public EmailLimitsConfig() {
        validate(System::getenv);
    }

    static void validate(final Function<String, String> environment) {
        PdfLimitsConfig.check(environment, "EMAIL_MAX_BYTES", Long::parseLong);
        PdfLimitsConfig.check(environment, "EMAIL_MAX_ATTACHMENT_BYTES", Long::parseLong);
        PdfLimitsConfig.check(environment, "EMAIL_MAX_ATTACHMENTS", Integer::parseInt);
        PdfLimitsConfig.check(environment, "EMAIL_MAX_NESTING_DEPTH", Integer::parseInt);
    }

}
