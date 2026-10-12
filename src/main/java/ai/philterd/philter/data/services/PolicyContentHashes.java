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
package ai.philterd.philter.data.services;

import ai.philterd.philter.services.encryption.EncryptionService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A policy's content hash: the same in every storage implementation, since ledger entries carry it. */
final class PolicyContentHashes {

    private static final Gson CANONICAL_GSON = new Gson();

    private PolicyContentHashes() {
    }

    /** See {@link PolicyVersionDataService#contentHash(String)}. */
    static String of(final String policyJson) {
        if (policyJson == null || policyJson.isBlank()) {
            return null;
        }
        return EncryptionService.hashSha256(canonicalizeJson(policyJson));
    }

    /**
     * Canonicalizes a JSON string: object keys are sorted recursively and the result is serialized
     * compactly. Array order is preserved (it is significant). Falls back to the original string if it
     * cannot be parsed as JSON.
     */
    private static String canonicalizeJson(final String json) {
        try {
            return CANONICAL_GSON.toJson(canonicalize(JsonParser.parseString(json)));
        } catch (final RuntimeException e) {
            return json;
        }
    }

    private static JsonElement canonicalize(final JsonElement element) {
        if (element.isJsonObject()) {
            final JsonObject source = element.getAsJsonObject();
            final JsonObject sorted = new JsonObject();
            final List<String> keys = new ArrayList<>(source.keySet());
            Collections.sort(keys);
            for (final String key : keys) {
                sorted.add(key, canonicalize(source.get(key)));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            final JsonArray source = element.getAsJsonArray();
            final JsonArray result = new JsonArray();
            for (final JsonElement child : source) {
                result.add(canonicalize(child));
            }
            return result;
        }
        return element;
    }

}
