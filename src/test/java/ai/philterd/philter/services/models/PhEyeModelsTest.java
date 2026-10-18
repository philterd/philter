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
package ai.philterd.philter.services.models;

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.services.policies.PolicyResolutionException;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhEyeModelsTest {

    private static final String CONFIG = "{\"max_len\":384,\"max_width\":12,"
            + "\"encoder_config\":{\"_name_or_path\":\"microsoft/deberta-v3-small\"}}";

    private static Path model(final Path directory, final String name, final boolean nested) throws Exception {
        final Path model = Files.createDirectories(directory.resolve(name));
        Files.writeString(model.resolve("gliner_config.json"), CONFIG);
        Files.writeString(model.resolve("tokenizer.json"), "{}");
        final Path onnx = nested ? Files.createDirectories(model.resolve("onnx")).resolve("model.onnx")
                : model.resolve("model.onnx");
        Files.write(onnx, new byte[100]);
        return model;
    }

    private static Policy pheyePolicy(final String modelPath) {
        return new Gson().fromJson("{\"identifiers\":{\"pheyes\":[{\"phEyeConfiguration\":{\"modelPath\":\""
                + modelPath + "\"}}],\"person\":{\"phEyeConfiguration\":{\"modelPath\":\"" + modelPath + "\"}}}}",
                Policy.class);
    }

    @Test
    void listsCompleteModelsByName(@TempDir final Path directory) throws Exception {
        model(directory, "pii-en-small", false);
        model(directory, "pii-en-large", true);
        // Not models: missing files, a name outside the rules, and a plain file.
        Files.writeString(Files.createDirectory(directory.resolve("incomplete")).resolve("gliner_config.json"), CONFIG);
        model(directory, ".hidden", false);
        Files.writeString(directory.resolve("README.md"), "models");

        final var models = new PhEyeModels(directory).list();

        assertEquals(2, models.size());
        assertEquals("pii-en-large", models.get(0).name());
        assertEquals("pii-en-small", models.get(1).name());
        assertEquals("microsoft/deberta-v3-small", models.get(1).encoder());
        assertEquals(384, models.get(1).maxLength());
        assertEquals(12, models.get(1).maxWidth());
        assertEquals(CONFIG.length() + 2 + 100, models.get(1).sizeBytes());
    }

    @Test
    void aMissingDirectoryHasNoModels(@TempDir final Path directory) {
        assertTrue(new PhEyeModels(directory.resolve("absent")).list().isEmpty());
    }

    @Test
    void aConfigurationWithoutTheOptionalFieldsIsStillAModel(@TempDir final Path directory) throws Exception {
        final Path model = model(directory, "bare", false);
        Files.writeString(model.resolve("gliner_config.json"), "{}");

        final var found = new PhEyeModels(directory).find("bare").orElseThrow();

        assertNull(found.encoder());
        assertNull(found.maxLength());
    }

    @Test
    void anUnreadableConfigurationIsNotAModel(@TempDir final Path directory) throws Exception {
        Files.writeString(model(directory, "broken", false).resolve("gliner_config.json"), "not json");
        assertTrue(new PhEyeModels(directory).list().isEmpty());
    }

    @Test
    void findRefusesNamesThatAreNotModelNames(@TempDir final Path directory) throws Exception {
        model(directory, "pii-en-small", false);
        final PhEyeModels models = new PhEyeModels(directory);

        assertTrue(models.find("pii-en-small").isPresent());
        assertFalse(models.find("..").isPresent());
        assertFalse(models.find(".").isPresent());
        assertFalse(models.find("../pii-en-small").isPresent());
        assertFalse(models.find(null).isPresent());
    }

    @Test
    void containsAModelByNameOrByThePathOfItsDirectory(@TempDir final Path directory, @TempDir final Path other)
            throws Exception {
        final Path model = model(directory, "pii-en-small", false);
        final Path link = Files.createSymbolicLink(other.resolve("link"), model);
        final PhEyeModels models = new PhEyeModels(directory);

        assertTrue(models.contains("pii-en-small"));
        assertTrue(models.contains(model.toString()));
        assertTrue(models.contains(link.toString()));
        assertTrue(models.contains(directory.resolve("pii-en-small").resolve("..").resolve("pii-en-small").toString()));
        assertFalse(models.contains("pii-en-large"));
        assertFalse(models.contains(directory.toString()));
        assertFalse(models.contains(model(other, "elsewhere", false).toString()));
        assertFalse(models.contains(""));
    }

    @Test
    void resolvesANameToTheModelsDirectory(@TempDir final Path directory) throws Exception {
        final Path model = model(directory, "pii-en-small", false);
        final Policy policy = pheyePolicy("pii-en-small");

        new PhEyeModels(directory).resolveNames(policy);

        assertEquals(model.toRealPath().toString(),
                policy.getIdentifiers().getPhEyes().get(0).getPhEyeConfiguration().getModelPath());
        assertEquals(model.toRealPath().toString(),
                policy.getIdentifiers().getPerson().getPhEyeConfiguration().getModelPath());
    }

    @Test
    void leavesAPathAsItIs(@TempDir final Path directory) {
        final Policy policy = pheyePolicy("/opt/models/gliner");

        new PhEyeModels(directory).resolveNames(policy);

        assertEquals("/opt/models/gliner",
                policy.getIdentifiers().getPhEyes().get(0).getPhEyeConfiguration().getModelPath());
    }

    @Test
    void refusesANameThatIsNotAModel(@TempDir final Path directory) {
        final PolicyResolutionException e = assertThrows(PolicyResolutionException.class,
                () -> new PhEyeModels(directory).resolveNames(pheyePolicy("pii-en-small")));
        assertTrue(e.getMessage().contains("'pii-en-small'"));
    }

    @Test
    void readsTheDirectoryFromTheEnvironment() {
        assertEquals(Path.of("/srv/models"),
                PhEyeModels.fromEnvironment(Map.of("PHEYE_MODELS_DIRECTORY", " /srv/models ")::get).getDirectory());
        assertEquals(Path.of(PhEyeModels.DEFAULT_DIRECTORY), PhEyeModels.fromEnvironment(name -> null).getDirectory());
    }

}
