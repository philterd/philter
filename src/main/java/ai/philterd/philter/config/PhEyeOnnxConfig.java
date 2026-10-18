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
 * Checks {@code PHEYE_ONNX_THREADS}, and the {@code pheye.onnx.threads} system property that overrides it, when
 * Philter starts. phileas-pheye-onnx reads them only when it loads a local model, so a value that is not a positive
 * whole number would otherwise fail every redaction that uses one.
 */
@Configuration
public class PhEyeOnnxConfig {

    public PhEyeOnnxConfig() {
        validate(System::getenv, System::getProperty);
    }

    static void validate(final Function<String, String> environment, final Function<String, String> properties) {
        check("PHEYE_ONNX_THREADS", environment.apply("PHEYE_ONNX_THREADS"));
        check("pheye.onnx.threads", properties.apply("pheye.onnx.threads"));
    }

    private static void check(final String name, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            if (Integer.parseInt(value.strip()) > 0) {
                return;
            }
        } catch (final NumberFormatException e) {
            // Refused below.
        }
        throw new IllegalStateException(name + " must be a positive whole number.");
    }

}
