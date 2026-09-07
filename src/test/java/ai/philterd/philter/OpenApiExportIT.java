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
package ai.philterd.philter;

import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full application against an in-memory MongoDB, fetches the OpenAPI specification that
 * springdoc generates from the Spring annotations, and writes it to {@code target/openapi.json}.
 * The CI docs workflow copies that file into the published documentation, so this test is the
 * build step that turns the annotation-derived specification into a distributable artifact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class OpenApiExportIT {

    /**
     * Registered as a nested class rather than imported directly: a nested {@code @TestConfiguration}
     * is processed after the application's own configuration, so its beans override the real ones.
     * An {@code @Import} of the same class is processed too early and the application's beans win,
     * which surfaces as PHILTER_ENCRYPTION_KEY being required.
     */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired
    private Environment environment;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;



    @Test
    void exportOpenApiSpecification() throws Exception {

        final int port = environment.getRequiredProperty("local.server.port", Integer.class);
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build();

        try (final HttpClient httpClient = HttpClient.newHttpClient()) {

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "The OpenAPI specification should be publicly accessible.");

            final JsonObject specification = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(specification.has("openapi"), "The response should be an OpenAPI specification.");
            assertFalse(specification.getAsJsonObject("paths").keySet().isEmpty(),
                    "The specification should document at least one endpoint.");

            // No serializer internals in a published contract. Gson's JsonObject and friends are
            // mutually recursive, so documenting them as beans both bloated the spec and made it
            // resolve differently from one build to the next.
            final JsonObject schemas = specification.getAsJsonObject("components").getAsJsonObject("schemas");
            final List<String> leaked = schemas.keySet().stream()
                    .filter(name -> name.startsWith("Json") || name.startsWith("Gson"))
                    .toList();
            assertTrue(leaked.isEmpty(), "Gson's own types must not appear in the published spec: " + leaked);

            // A status the endpoint cannot return is worse than an undocumented one: this GET has
            // always answered 404 for a missing list while the spec advertised 409.
            final JsonObject getOneList = specification.getAsJsonObject("paths")
                    .getAsJsonObject("/api/lists/{name}").getAsJsonObject("get").getAsJsonObject("responses");
            assertTrue(getOneList.has("404"), "A missing list is a 404, and the spec must say so.");
            assertFalse(getOneList.has("409"), "This endpoint never answers 409.");

            final JsonObject paths = specification.getAsJsonObject("paths");
            final java.util.Set<String> live = new java.util.TreeSet<>();
            mappings.getHandlerMethods().forEach((mapping, handler) -> {
                if (!handler.getBeanType().getPackageName().equals("ai.philterd.philter.api.controllers")) return;
                for (String path : mapping.getPatternValues()) {
                    for (var method : mapping.getMethodsCondition().getMethods()) {
                        live.add(method.name().toLowerCase(java.util.Locale.ROOT) + " " + path);
                    }
                }
            });
            final java.util.Set<String> documented = new java.util.TreeSet<>();
            paths.entrySet().forEach(path -> path.getValue().getAsJsonObject().entrySet().forEach(operation -> {
                if (java.util.Set.of("get", "post", "put", "delete", "patch").contains(operation.getKey())) {
                    documented.add(operation.getKey() + " " + path.getKey());
                }
            }));
            assertEquals(live, documented, "Every live operation must be documented, with no nonexistent operations.");
            final String inventory = Files.readString(Path.of("docs/docs/api_and_sdks/api/endpoint_inventory.md"));
            for (String endpoint : live) {
                final int separator = endpoint.indexOf(' ');
                assertTrue(inventory.contains("| " + endpoint.substring(0, separator).toUpperCase(java.util.Locale.ROOT)
                        + " | `" + endpoint.substring(separator + 1) + "` |"), "Missing inventory entry: " + endpoint);
            }
            final java.util.Set<String> normalizedPaths = new java.util.HashSet<>();
            paths.keySet().forEach(path -> assertTrue(normalizedPaths.add(path.replaceAll("\\{[^}]+}", "{}")),
                    "Equivalent path templates must use the same parameter name: " + path));

            for (String path : java.util.List.of("/api/contexts", "/api/documents", "/api/ledger", "/api/redact-lists", "/api/signing-key")) {
                final JsonObject schema = paths.getAsJsonObject(path).getAsJsonObject("get")
                        .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                        .getAsJsonObject("application/json").getAsJsonObject("schema");
                assertFalse(schema.has("type") && "string".equals(schema.get("type").getAsString()), path);
            }
            final JsonObject filtering = paths.getAsJsonObject("/api/filter").getAsJsonObject("post");
            assertEquals(java.util.Set.of("application/pdf", "application/zip", "text/plain"), filtering
                    .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content").keySet());
            assertTrue(filtering.getAsJsonObject("responses").getAsJsonObject("202").getAsJsonObject("content").has("application/json"));
            assertTrue(schemas.getAsJsonObject("GetRedactionStatusResponse").getAsJsonObject("properties").has("effectiveConfigurationHash"));
            assertTrue(schemas.getAsJsonObject("LedgerEntryView").getAsJsonObject("properties").has("effectiveHash"));

            // Make the published artifact reproducible: identical code must produce a byte-identical
            // spec so the committed file only changes when the API actually changes. Two sources of
            // run-to-run noise are removed here.
            //
            // 1. springdoc bakes this test's random server port into servers[].url. Replace it with a
            //    stable, host-relative server so the URL does not change every build (and so the
            //    published spec does not point clients at a dead localhost port).
            final JsonArray servers = new JsonArray();
            final JsonObject server = new JsonObject();
            server.addProperty("url", "/");
            server.addProperty("description", "Philter API");
            servers.add(server);
            specification.add("servers", servers);

            // 2. springdoc emits some maps (e.g. per-operation responses) in HashMap order, so keys
            //    like "401"/"500" reorder between runs. Sorting all object keys canonically removes
            //    that noise; key order is not significant in OpenAPI/JSON.
            final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            final Path output = Path.of("target", "openapi.json");
            Files.writeString(output, gson.toJson(canonicalize(specification)));
            if (!Boolean.getBoolean("philter.test.updateOpenApi")) {
                assertEquals(canonicalize(specification), canonicalize(JsonParser.parseString(Files.readString(
                        Path.of("docs/docs/api_and_sdks/openapi.json")))),
                        "Committed OpenAPI drifted. Inspect target/openapi.json and refresh the published artifact.");
            }


        }

    }

    /**
     * Conventional ordering for well-known OpenAPI/JSON-Schema keys. Keys that appear here are emitted
     * in this order (so the document reads like a normal OpenAPI spec — {@code openapi} and {@code info}
     * first, not an alphabetized jumble); any other key (status codes, schema names, property names) is
     * emitted alphabetically after them. The list is de-duplicated; the first occurrence sets the order.
     */
    private static final java.util.List<String> KEY_ORDER = java.util.List.of(
            // OpenAPI document root
            "openapi", "info", "jsonSchemaDialect", "servers", "paths", "webhooks", "components", "security", "tags", "externalDocs",
            // Info / contact / license
            "title", "summary", "description", "termsOfService", "contact", "license", "version", "identifier",
            // Operation
            "operationId", "parameters", "requestBody", "responses", "callbacks", "deprecated",
            // Parameter / media type
            "name", "in", "required", "style", "explode", "schema", "example", "examples", "content", "encoding",
            // Components
            "schemas", "securitySchemes", "headers", "links",
            // Schema (JSON Schema)
            "type", "format", "default", "properties", "items", "enum", "$ref", "additionalProperties", "uniqueItems",
            // Shared (server / contact / external docs)
            "url", "email");

    /**
     * Returns a copy of the given JSON with the keys of every object ordered deterministically, so that
     * serialization does not depend on the map iteration order springdoc happens to use. Well-known
     * OpenAPI keys keep their conventional order ({@link #KEY_ORDER}); all others fall back to
     * alphabetical. Arrays keep their order (it is significant); scalars are returned as-is.
     */
    private static JsonElement canonicalize(final JsonElement element) {

        if (element.isJsonObject()) {
            final JsonObject ordered = new JsonObject();
            element.getAsJsonObject().entrySet().stream()
                    .sorted(java.util.Comparator
                            .<java.util.Map.Entry<String, JsonElement>>comparingInt(entry -> keyRank(entry.getKey()))
                            .thenComparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> ordered.add(entry.getKey(), canonicalize(entry.getValue())));
            return ordered;
        }

        if (element.isJsonArray()) {
            final JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(item -> array.add(canonicalize(item)));
            return array;
        }

        return element;

    }

    /** Rank for the conventional key order; unknown keys sort after known ones (then alphabetically). */
    private static int keyRank(final String key) {
        final int index = KEY_ORDER.indexOf(key);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

}
