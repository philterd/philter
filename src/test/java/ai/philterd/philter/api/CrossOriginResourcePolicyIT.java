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
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Every response Philter serves carries {@code Cross-Origin-Resource-Policy: same-origin}: the API's
 * successful and error responses, the actuator endpoints, and the documentation. Firewall refusals are
 * checked in {@link RefusedPathErrorsIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true",
                "philter.docs.location=classpath:/smoke-docs/"})
class CrossOriginResourcePolicyIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String CORP = "Cross-Origin-Resource-Policy";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private HttpClient httpClient;
    private String baseUrl;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "corp-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, "user", policyDataService, contextDataService, "test").isSuccessful());
        key = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> get(final String path, final String apiKey) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A successful API response carries Cross-Origin-Resource-Policy: same-origin")
    void successfulApiResponse() throws Exception {
        final HttpResponse<String> response = get("/api/policies", key);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("same-origin", response.headers().firstValue(CORP).orElse(null), response.headers().toString());
    }

    @ParameterizedTest
    @CsvSource({"/api/policies, 401", "/api/policies/no-such-policy, 404"})
    @DisplayName("An API error response carries Cross-Origin-Resource-Policy: same-origin")
    void apiErrorResponses(final String path, final int status) throws Exception {
        final HttpResponse<String> response = get(path, status == 401 ? null : key);
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("same-origin", response.headers().firstValue(CORP).orElse(null), response.headers().toString());
    }

    @Test
    @DisplayName("The actuator endpoints carry Cross-Origin-Resource-Policy: same-origin")
    void actuatorResponse() throws Exception {
        final HttpResponse<String> response = get("/actuator/health", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("same-origin", response.headers().firstValue(CORP).orElse(null), response.headers().toString());
    }

    @Test
    @DisplayName("The documentation carries the security headers but can be cached")
    void documentationResponse() throws Exception {
        final HttpResponse<String> response = get("/public/docs/index.html", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("same-origin", response.headers().firstValue(CORP).orElse(null), response.headers().toString());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("SAMEORIGIN", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(response.headers().firstValue("Pragma").isEmpty(), response.headers().toString());
        assertTrue(response.headers().allValues("Cache-Control").stream().noneMatch(value -> value.contains("no-store")),
                response.headers().toString());
    }

}
