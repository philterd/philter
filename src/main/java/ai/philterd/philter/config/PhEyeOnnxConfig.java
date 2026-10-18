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

import ai.philterd.philter.services.models.PhEyeModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Checks {@code PHEYE_ONNX_THREADS}, and the {@code pheye.onnx.threads} system property that overrides it, when
 * Philter starts. phileas-pheye-onnx reads them only when it loads a local model, so a value that is not a positive
 * whole number would otherwise fail every redaction that uses one. {@code PHEYE_ONNX_DEVICE} and
 * {@code pheye.onnx.device} are checked the same way. Also checks that a {@code PHEYE_MODELS_DIRECTORY}
 * that is set is a directory, and logs the models in it.
 */
@Configuration
public class PhEyeOnnxConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhEyeOnnxConfig.class);

    public PhEyeOnnxConfig() {
        validate(System::getenv, System::getProperty);
        final PhEyeModels models = PhEyeModels.shared();
        LOGGER.info("Local PhEye models in {}: {}", models.getDirectory(),
                models.list().stream().map(PhEyeModels.PhEyeModel::name).toList());
    }

    static void validate(final Function<String, String> environment, final Function<String, String> properties) {
        check("PHEYE_ONNX_THREADS", environment.apply("PHEYE_ONNX_THREADS"));
        check("pheye.onnx.threads", properties.apply("pheye.onnx.threads"));
        checkDevice("PHEYE_ONNX_DEVICE", environment.apply("PHEYE_ONNX_DEVICE"));
        checkDevice("pheye.onnx.device", properties.apply("pheye.onnx.device"));
        final String directory = environment.apply(PhEyeModels.DIRECTORY_ENV);
        if (directory != null && !directory.isBlank() && !Files.isDirectory(Path.of(directory.strip()))) {
            throw new IllegalStateException(PhEyeModels.DIRECTORY_ENV + " must be a directory.");
        }
    }

    /** {@code cpu}, {@code cuda}, or {@code cuda:<n>}, as phileas-pheye-onnx reads it, ignoring case. */
    private static void checkDevice(final String name, final String value) {
        if (value == null || value.isBlank() || value.strip().toLowerCase(java.util.Locale.ROOT).matches("cpu|cuda|cuda:\\d{1,9}")) {
            return;
        }
        throw new IllegalStateException(name + " must be cpu, cuda, or cuda:<device number>.");
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
