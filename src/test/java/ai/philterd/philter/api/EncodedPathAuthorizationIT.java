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

import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request is authenticated and its scope checked by the path it is routed by, not the raw request URI.
 * Spring MVC routes on the decoded path, so {@code /%61pi/documents} reaches the {@code /api/documents} handler; it
 * must get exactly the treatment {@code /api/documents} gets. These reach storage only through the API, so they run
 * against every storage implementation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class EncodedPathAuthorizationIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    /** Each path written plainly and with a percent-encoded character Spring MVC decodes before routing. */
    private static final String[][] DOCUMENTS = {{"/api/documents"}, {"/%61pi/documents"}, {"/api/%64ocuments"}};

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private HttpClient httpClient;
    private String baseUrl;
    private String username;
    private String userId;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        username = "encoded-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        userId = userService.findByUsername(username).getId();
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String key(final ApiKeyScope... scopes) {
        return apiKeyDataService.createApiKey("req", userId, "test",
                java.util.Arrays.stream(scopes).map(ApiKeyScope::getScope).collect(java.util.stream.Collectors.toSet())).getMessage();
    }

    private int get(final String path, final String apiKey) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("A key with the scope reads the endpoint by any spelling of its path")
    void aKeyWithTheScopeIsServed() throws Exception {
        final String documentsKey = key(ApiKeyScope.DOCUMENTS_READ);
        for (final String[] path : DOCUMENTS) {
            assertEquals(200, get(path[0], documentsKey), path[0]);
        }
    }

    @Test
    @DisplayName("A key without the scope is refused by any spelling of the path")
    void aKeyWithoutTheScopeIsRefused() throws Exception {
        final String redactKey = key(ApiKeyScope.REDACT);
        for (final String[] path : DOCUMENTS) {
            assertEquals(403, get(path[0], redactKey), path[0]);
        }
    }

    @Test
    @DisplayName("A deactivated user's key is refused by any spelling of the path")
    void aDeactivatedUsersKeyIsRefused() throws Exception {
        final String documentsKey = key(ApiKeyScope.DOCUMENTS_READ);
        assertTrue(userService.deactivateUser("req", userService.findByUsername(username), "test").isSuccessful());
        for (final String[] path : DOCUMENTS) {
            assertEquals(401, get(path[0], documentsKey), path[0]);
        }
    }

    @Test
    @DisplayName("A missing or unknown key is refused by any spelling of the path")
    void aMissingOrUnknownKeyIsRefused() throws Exception {
        for (final String[] path : DOCUMENTS) {
            assertEquals(401, get(path[0], null), path[0]);
            assertEquals(401, get(path[0], "sk_" + "a".repeat(32)), path[0]);
        }
    }

    @Test
    @DisplayName("A public endpoint stays public by any spelling of its path")
    void aPublicEndpointStaysPublic() throws Exception {
        for (final String path : new String[]{"/api/health", "/%61pi/health", "/api/%68ealth"}) {
            assertEquals(200, get(path, null), path);
        }
    }

}
