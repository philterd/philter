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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bson.types.ObjectId;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configuring a webhook over real HTTP, through the filter chain, scope check, and the stored user. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class WebhookApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private ObjectId seedUser(final String role) {
        final String username = "hook-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    private String seedKey(final ObjectId userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private String idOf(final String apiKey) {
        return apiKeyDataService.findOneByApiKey(apiKey).getId().toHexString();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A user sets, reads, and removes its webhook, and each change is audited")
    void configuresAWebhook() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = seedKey(user, Set.of("webhooks:read", "webhooks:write"));

        final HttpResponse<String> refused = send("PUT", "/api/webhook", key,
                "{\"url\":\"https://127.0.0.1/hook\",\"secret\":\"a-secret-of-16ch\"}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("private or loopback"), refused.body());

        final HttpResponse<String> set = send("PUT", "/api/webhook", key,
                "{\"url\":\"https://93.184.216.34/hook\",\"secret\":\"a-secret-of-16ch\"}");
        assertEquals(200, set.statusCode(), set.body());
        assertEquals("https://93.184.216.34/hook", userService.findOneById(user).getWebhookUrl());
        assertEquals("a-secret-of-16ch", userService.findOneById(user).getWebhookSecret());

        final HttpResponse<String> read = send("GET", "/api/webhook", key, null);
        assertEquals(200, read.statusCode(), read.body());
        final JsonObject readJson = gson.fromJson(read.body(), JsonObject.class);
        assertEquals("https://93.184.216.34/hook", readJson.get("url").getAsString());
        assertTrue(readJson.get("secretSet").getAsBoolean());
        assertFalse(read.body().contains("a-secret-of-16ch"), "the secret is never returned");

        assertEquals(204, send("DELETE", "/api/webhook", key, null).statusCode());
        assertNull(userService.findOneById(user).getWebhookUrl());

        final String keyId = idOf(key);
        final String admin = seedKey(seedUser("admin"), ApiKeyScope.all());
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=100", admin, null);
        final Set<String> recorded = new HashSet<>();
        for (final var element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            if (event.has("associatedObject") && user.toHexString().equals(event.get("associatedObject").getAsString())
                    && event.has("details") && event.get("details").getAsString().contains("api_key: " + keyId)) {
                recorded.add(event.get("event").getAsString());
            }
        }
        assertEquals(Set.of("webhook_configured", "webhook_removed"), recorded,
                "the refused attempt is not audited, the two changes are");

    }

    @Test
    @DisplayName("Another user's webhook is out of reach without an administrator and cross-user access")
    void anotherUsersWebhook() throws Exception {
        final ObjectId other = seedUser("user");
        final String otherUsername = userService.findOneById(other).getUsername();
        final String mine = seedKey(seedUser("user"), ApiKeyScope.all());

        assertEquals(404, send("GET", "/api/webhook?owner=" + otherUsername, mine, null).statusCode());
        assertEquals(404, send("PUT", "/api/webhook?owner=" + otherUsername, mine,
                "{\"url\":\"https://93.184.216.34/hook\",\"secret\":\"a-secret-of-16ch\"}").statusCode());
        assertNull(userService.findOneById(other).getWebhookUrl());
    }

}
