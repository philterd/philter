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

import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.PhEye;
import ai.philterd.philter.services.policies.PolicyResolutionException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The local PhEye models in {@code PHEYE_MODELS_DIRECTORY}. Each subdirectory holding a complete model is a model
 * named after the directory, and a policy names it with a ph-eye {@code modelPath} that has no path separator. The
 * directory is read on every call, so a model added or removed is seen without a restart; a model Philter has already
 * loaded stays loaded until it restarts.
 */
public final class PhEyeModels {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhEyeModels.class);

    public static final String DIRECTORY_ENV = "PHEYE_MODELS_DIRECTORY";
    public static final String DEFAULT_DIRECTORY = "/opt/philter/models";

    /** A model name: letters, digits, dots, underscores, and hyphens, starting with a letter or digit. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final PhEyeModels SHARED = fromEnvironment(System::getenv);

    private final Path directory;

    public PhEyeModels(final Path directory) {
        this.directory = directory;
    }

    public static PhEyeModels fromEnvironment(final Function<String, String> environment) {
        final String value = environment.apply(DIRECTORY_ENV);
        return new PhEyeModels(Path.of(value == null || value.isBlank() ? DEFAULT_DIRECTORY : value.strip()));
    }

    /** The models in the deployment's directory. */
    public static PhEyeModels shared() {
        return SHARED;
    }

    public Path getDirectory() {
        return directory;
    }

    /** A model a policy can name. */
    public record PhEyeModel(String name, Path path, String encoder, Integer maxLength, Integer maxWidth,
                             long sizeBytes) {
    }

    /** The complete models in the directory, by name. Empty when the directory does not exist. */
    public List<PhEyeModel> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<PhEyeModel> models = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (final Path entry : entries.sorted().toList()) {
                read(entry).ifPresent(models::add);
            }
        } catch (final IOException e) {
            LOGGER.warn("Could not list the models in {}: {}", directory, e.toString());
        }
        return models;
    }

    public Optional<PhEyeModel> find(final String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return read(directory.resolve(name));
    }

    /** True for a {@code modelPath} that names a model rather than giving a path. */
    public static boolean isName(final String modelPath) {
        return modelPath != null && !modelPath.contains("/") && !modelPath.contains("\\");
    }

    /**
     * True when the {@code modelPath} is a model in this directory: its name, or the path of its directory.
     */
    public boolean contains(final String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return false;
        }
        if (isName(modelPath)) {
            return find(modelPath).isPresent();
        }
        try {
            final Path real = Path.of(modelPath).toRealPath();
            return list().stream().anyMatch(model -> model.path().equals(real));
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Replaces each ph-eye {@code modelPath} that names a model with the path of the model's directory.
     * @throws PolicyResolutionException When a name is not a model in the directory.
     */
    public void resolveNames(final Policy policy) {
        final Identifiers identifiers = policy.getIdentifiers();
        if (identifiers == null) {
            return;
        }
        if (identifiers.getPhEyes() != null) {
            for (final PhEye phEye : identifiers.getPhEyes()) {
                resolveName(phEye);
            }
        }
        resolveName(identifiers.getPerson());
    }

    private void resolveName(final PhEye phEye) {
        if (phEye == null || phEye.getPhEyeConfiguration() == null) {
            return;
        }
        final String modelPath = phEye.getPhEyeConfiguration().getModelPath();
        if (modelPath == null || modelPath.isBlank() || !isName(modelPath)) {
            return;
        }
        final PhEyeModel model = find(modelPath).orElseThrow(() -> new PolicyResolutionException(
                "There is no model named '" + modelPath + "'. GET /api/models lists the models this deployment has."));
        phEye.getPhEyeConfiguration().setModelPath(model.path().toString());
    }

    private static Optional<PhEyeModel> read(final Path dir) {
        final String name = dir.getFileName().toString();
        if (!NAME.matcher(name).matches() || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        final Path config = dir.resolve("gliner_config.json");
        final Path tokenizer = dir.resolve("tokenizer.json");
        Path onnx = dir.resolve("onnx").resolve("model.onnx");
        if (!Files.isRegularFile(onnx)) {
            onnx = dir.resolve("model.onnx");
        }
        if (!Files.isRegularFile(config) || !Files.isRegularFile(tokenizer) || !Files.isRegularFile(onnx)) {
            LOGGER.debug("{} is not a complete model: it needs gliner_config.json, tokenizer.json, and model.onnx.", dir);
            return Optional.empty();
        }
        try {
            final JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            String encoder = null;
            if (json.get("encoder_config") instanceof JsonObject encoderConfig) {
                encoder = string(encoderConfig.get("_name_or_path"));
            }
            return Optional.of(new PhEyeModel(name, dir.toRealPath(), encoder, integer(json.get("max_len")),
                    integer(json.get("max_width")), Files.size(config) + Files.size(tokenizer) + Files.size(onnx)));
        } catch (final Exception e) {
            LOGGER.warn("Skipping the model in {}: its gliner_config.json could not be read: {}", dir, e.toString());
            return Optional.empty();
        }
    }

    private static String string(final JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static Integer integer(final JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : null;
    }

}
