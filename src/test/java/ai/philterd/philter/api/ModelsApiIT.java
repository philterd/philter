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
package ai.philterd.philter.api;

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The models API and policies that name a model, against src/test/resources/models, which the build sets as
 * PHEYE_MODELS_DIRECTORY. Its test-fixture model detects the first two words of any text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ModelsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String REDACT = "[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{REDACTED-%t}}}\"}]";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private HttpClient httpClient;
    private String baseUrl;
    private String userId;
    private String apiKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "models-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "user", policyDataService,
                contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        userId = userService.findByUsername(username).getId();
        apiKey = apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> get(final String path, final String key) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + key)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> savePolicy(final String name, final String modelPath) throws Exception {
        final String policy = "{\"identifiers\":{\"pheyes\":[{\"phEyeConfiguration\":{\"modelPath\":\"" + modelPath
                + "\",\"labels\":[\"person\"]},\"phEyeFilterStrategies\":" + REDACT + "}]}}";
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies?name=" + name))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(policy)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("GET /api/models lists the models in PHEYE_MODELS_DIRECTORY, without their paths")
    void listsTheModels() throws Exception {
        final HttpResponse<String> response = get("/api/models", apiKey);

        assertEquals(200, response.statusCode(), response.body());
        final JsonArray models = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("models");
        assertEquals(1, models.size(), response.body());
        final JsonObject model = models.get(0).getAsJsonObject();
        assertEquals("test-fixture", model.get("name").getAsString());
        assertEquals("synthetic-test-fixture", model.get("encoder").getAsString());
        assertEquals(384, model.get("maxLength").getAsInt());
        assertEquals(12, model.get("maxWidth").getAsInt());
        assertTrue(model.get("sizeBytes").getAsLong() > 0);
        assertFalse(response.body().contains("src/test/resources"), "a model's location is not disclosed");
    }

    @Test
    @DisplayName("GET /api/models/{modelName} returns one model, and 404 for a name that is not a model")
    void getsOneModel() throws Exception {
        final HttpResponse<String> found = get("/api/models/test-fixture", apiKey);
        assertEquals(200, found.statusCode(), found.body());
        assertEquals("test-fixture", JsonParser.parseString(found.body()).getAsJsonObject().get("name").getAsString());

        assertEquals(404, get("/api/models/absent", apiKey).statusCode());
        // Philter refuses a path with a dot segment before it reaches a controller.
        assertEquals(400, get("/api/models/..", apiKey).statusCode());
    }

    @Test
    @DisplayName("The models API requires the policies:read scope")
    void requiresPoliciesRead() throws Exception {
        final String redactOnly = apiKeyDataService.createApiKey("req", userId, "test",
                Set.of(ApiKeyScope.REDACT.getScope())).getMessage();

        assertEquals(403, get("/api/models", redactOnly).statusCode());
        assertEquals(403, get("/api/models/test-fixture", redactOnly).statusCode());
        assertEquals(401, get("/api/models", "sk_notakeynotakeynotakeynotakey00").statusCode());
    }

    @Test
    @DisplayName("A policy that names a model redacts with it")
    void aPolicyNamingAModelRedactsWithIt() throws Exception {
        final HttpResponse<String> saved = savePolicy("by-name", "test-fixture");
        assertEquals(201, saved.statusCode(), saved.body());

        final HttpResponse<String> redacted = httpClient.send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/filter?p=by-name")).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("Alice Bob went home.")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, redacted.statusCode(), redacted.body());
        assertEquals("{{{REDACTED-person}}} went home.", redacted.body());

        // The stored policy keeps the name, not the path it resolves to.
        final HttpResponse<String> stored = get("/api/policies/by-name", apiKey);
        assertTrue(stored.body().contains("\"modelPath\":\"test-fixture\"") || stored.body().contains("\"modelPath\": \"test-fixture\""),
                stored.body());
    }

    @Test
    @DisplayName("A policy that names a model the deployment does not have is refused when it is saved")
    void aPolicyNamingAnUnknownModelIsRefused() throws Exception {
        final HttpResponse<String> refused = savePolicy("unknown-model", "pii-en-large");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("There is no model named 'pii-en-large'"), refused.body());
    }

    @Test
    @DisplayName("A policy that names a path outside the models directory is still refused unless allowlisted")
    void aPathOutsideTheModelsDirectoryIsRefused() throws Exception {
        final HttpResponse<String> refused = savePolicy("outside", "/etc");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("not a model this deployment has"), refused.body());
    }

}
