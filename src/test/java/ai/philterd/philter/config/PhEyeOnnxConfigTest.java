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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhEyeOnnxConfigTest {

    private static final Map<String, String> NONE = Map.of();

    @Test
    void acceptsUnsetBlankAndPositiveWholeNumbers() {
        assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(NONE::get, NONE::get));
        assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(Map.of("PHEYE_ONNX_THREADS", " 4 ")::get,
                Map.of("pheye.onnx.threads", "")::get));
        assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(NONE::get, Map.of("pheye.onnx.threads", "1")::get));
    }

    @Test
    void refusesAValueThatIsNotAPositiveWholeNumber() {
        for (final String value : new String[] {"0", "-2", "two", "1.5", "3000000000"}) {
            assertThrows(IllegalStateException.class,
                    () -> PhEyeOnnxConfig.validate(Map.of("PHEYE_ONNX_THREADS", value)::get, NONE::get));
            assertThrows(IllegalStateException.class,
                    () -> PhEyeOnnxConfig.validate(NONE::get, Map.of("pheye.onnx.threads", value)::get));
        }
    }

    @Test
    void refusesAModelsDirectoryThatIsNotADirectory(@TempDir final Path directory) throws Exception {
        assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(Map.of("PHEYE_MODELS_DIRECTORY", directory.toString())::get,
                NONE::get));
        assertThrows(IllegalStateException.class, () -> PhEyeOnnxConfig.validate(
                Map.of("PHEYE_MODELS_DIRECTORY", directory.resolve("absent").toString())::get, NONE::get));
        final Path file = Files.writeString(directory.resolve("file"), "x");
        assertThrows(IllegalStateException.class,
                () -> PhEyeOnnxConfig.validate(Map.of("PHEYE_MODELS_DIRECTORY", file.toString())::get, NONE::get));
    }

    @Test
    void acceptsTheDevicesPhileasPheyeOnnxReads() {
        for (final String value : new String[] {"cpu", "CPU", "cuda", "Cuda", "cuda:0", " cuda:3 ", ""}) {
            assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(Map.of("PHEYE_ONNX_DEVICE", value)::get, NONE::get), value);
            assertDoesNotThrow(() -> PhEyeOnnxConfig.validate(NONE::get, Map.of("pheye.onnx.device", value)::get), value);
        }
    }

    @Test
    void refusesAnyOtherDevice() {
        for (final String value : new String[] {"gpu", "cuda:", "cuda:-1", "cuda:+1", "cuda:one", "cuda1", "cuda:1234567890"}) {
            assertThrows(IllegalStateException.class,
                    () -> PhEyeOnnxConfig.validate(Map.of("PHEYE_ONNX_DEVICE", value)::get, NONE::get), value);
            assertThrows(IllegalStateException.class,
                    () -> PhEyeOnnxConfig.validate(NONE::get, Map.of("pheye.onnx.device", value)::get), value);
        }
    }

}
