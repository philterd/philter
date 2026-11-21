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
package ai.philterd.philter.services.policies;

import ai.philterd.phileas.policy.Policy;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Users copy the managed policies as a starting point, so they must use the key names the Phileas policy model
 * defines. Parsing alone does not catch a wrong key: Gson still reads a deprecated alternate name (such as
 * {@code zipCodeFilterStrategy}) and silently skips a key it does not know.
 */
class ManagedPolicyKeysTest {

    @Test
    @DisplayName("Every managed policy uses only the policy model's primary key names")
    void everyManagedPolicyUsesPrimaryKeyNames() throws Exception {

        final Path directory = Path.of(getClass().getResource("/managed-policies/").toURI());
        final List<Path> files;
        try (final Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no managed policy files found in " + directory);

        final List<String> problems = new ArrayList<>();
        for (final Path file : files) {
            final JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            checkKeys(json, Policy.class, file.getFileName().toString() + ":", problems);
        }

        assertEquals(List.of(), problems);

    }

    @Test
    @DisplayName("The key check reports a deprecated name and an unknown key")
    void keyCheckReportsDeprecatedAndUnknownKeys() {

        final JsonElement json = JsonParser.parseString("""
                {"identifiers": {
                  "zipCode": {"zipCodeFilterStrategy": [{"strategy": "REDACT"}]},
                  "ssn": {"ssnFilterStrategies": [{"strategy": "REDACT", "redactionFormt": "x"}]}
                }}""");

        final List<String> problems = new ArrayList<>();
        checkKeys(json, Policy.class, "policy:", problems);

        assertEquals(List.of(
                "policy:/identifiers/zipCode/zipCodeFilterStrategy is a deprecated name, use zipCodeFilterStrategies",
                "policy:/identifiers/ssn/ssnFilterStrategies[0]/redactionFormt is not a key of SsnFilterStrategy"),
                problems);

    }

    /** Checks each key in {@code json} against the fields Gson maps for {@code type}, recursing into model types. */
    private static void checkKeys(final JsonElement json, final Type type, final String path, final List<String> problems) {

        final Class<?> rawType = TypeToken.get(type).getRawType();

        if (json.isJsonArray() && List.class.isAssignableFrom(rawType)) {
            final Type elementType = ((ParameterizedType) type).getActualTypeArguments()[0];
            int index = 0;
            for (final JsonElement element : json.getAsJsonArray()) {
                checkKeys(element, elementType, path + "[" + index++ + "]", problems);
            }
            return;
        }

        if (json.isJsonObject() && Map.class.isAssignableFrom(rawType)) {
            // The keys are names the policy chooses, such as a generator's name.
            final Type valueType = ((ParameterizedType) type).getActualTypeArguments()[1];
            for (final Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                checkKeys(entry.getValue(), valueType, path + "/" + entry.getKey(), problems);
            }
            return;
        }

        if (!json.isJsonObject() || !rawType.getName().startsWith("ai.philterd.phileas.")) {
            // A value, or free-form JSON such as the policy's metadata.
            return;
        }

        for (final Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            final String key = entry.getKey();
            final String keyPath = path + "/" + key;
            final Field field = fieldNamed(rawType, key);
            if (field != null) {
                checkKeys(entry.getValue(), field.getGenericType(), keyPath, problems);
            } else {
                final String primaryName = primaryNameOfAlternate(rawType, key);
                problems.add(primaryName != null
                        ? keyPath + " is a deprecated name, use " + primaryName
                        : keyPath + " is not a key of " + rawType.getSimpleName());
            }
        }

    }

    /** The field Gson reads for {@code key} by its primary name: the {@code @SerializedName} value, else the field name. */
    private static Field fieldNamed(final Class<?> type, final String key) {
        for (final Field field : serializedFields(type)) {
            final SerializedName name = field.getAnnotation(SerializedName.class);
            if (key.equals(name != null ? name.value() : field.getName())) {
                return field;
            }
        }
        return null;
    }

    /** The primary name of the field that lists {@code key} as an alternate, or null if none does. */
    private static String primaryNameOfAlternate(final Class<?> type, final String key) {
        for (final Field field : serializedFields(type)) {
            final SerializedName name = field.getAnnotation(SerializedName.class);
            if (name != null && Arrays.asList(name.alternate()).contains(key)) {
                return name.value();
            }
        }
        return null;
    }

    /** The fields Gson serializes for {@code type}, including inherited ones. */
    private static List<Field> serializedFields(final Class<?> type) {
        final List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (final Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

}
