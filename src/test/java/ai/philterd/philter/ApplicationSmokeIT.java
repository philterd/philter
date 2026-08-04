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

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the surfaces outside the API still work when the application is actually running.
 *
 * <p>The API has thorough coverage, but three things it depends on have almost none. The Vaadin
 * dashboard is roughly a third of the codebase at under 1% line coverage, so a Spring or Vaadin
 * upgrade that broke it would pass the whole suite. The actuator endpoints are what a load balancer
 * and a Prometheus scraper depend on, and nothing else asserts they respond. Swagger UI is what the
 * documentation points developers at.
 *
 * <p>These assertions are deliberately shallow: this is a smoke test, not a UI test. It answers "does
 * a real running Philter still serve its dashboard, its probes, its metrics, and its API reference",
 * which is the question a framework upgrade raises and which unit tests cannot answer. Each check
 * looks at the response body, not only the status code, because a friendly error page is also a 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ApplicationSmokeIT {

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
    private UserService userService;

    @Autowired
    private ApiKeyDataService apiKeyDataService;

    @Autowired
    private PolicyDataService policyDataService;

    @Autowired
    private ContextDataService contextDataService;

    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;

    @BeforeEach
    void setUp() {

        // Do not follow redirects: a redirect to the login page must not be mistaken for a page that
        // rendered.
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        final String username = "smoke-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");

        final ObjectId userId = userService.findByUsername(username).getId();
        apiKey = apiKeyDataService.createApiKey("req", userId, "test").getMessage();

    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    @Test
    @DisplayName("The dashboard login page renders")
    void dashboardRenders() throws Exception {

        final HttpResponse<String> response = get("/login");

        assertEquals(200, response.statusCode());
        // Vaadin stamps its generated bootstrap page, so this confirms the Vaadin servlet produced the
        // response rather than an error page or a static file.
        assertTrue(response.body().contains("auto-generated by Vaadin"),
                "the login page must be rendered by Vaadin: " + head(response.body()));
        assertTrue(response.body().contains("window.Vaadin"),
                "the Vaadin client bootstrap must be present: " + head(response.body()));

    }

    @Test
    @DisplayName("The health probe reports UP")
    void healthReportsUp() throws Exception {

        final HttpResponse<String> response = get("/actuator/health");

        assertEquals(200, response.statusCode());
        // A load balancer reads this. A 200 carrying DOWN would still fail the deployment it guards.
        assertTrue(response.body().contains("\"status\":\"UP\""),
                "health must report UP: " + response.body());

    }

    @Test
    @DisplayName("Prometheus metrics are exported, including Philter's own")
    void prometheusExportsMetrics() throws Exception {

        // Philter's counters are registered on first use, so a freshly booted application exports none
        // of them. Make one authenticated request first, or this asserts on an empty registry.
        final HttpResponse<String> apiCall = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build());
        assertEquals(200, apiCall.statusCode(), "the request that generates the metric must succeed");

        final HttpResponse<String> response = get("/actuator/prometheus");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("jvm_"), "the standard JVM metrics must be exported");
        // The whole chain: the filter increments a counter, micrometer holds it, and the Prometheus
        // registry renders it. Unit tests cover the increment; only this covers the export.
        assertTrue(response.body().contains("philter_api_requests_total"),
                "Philter's own metrics must reach the Prometheus endpoint");

    }

    @Test
    @DisplayName("The API reference is served")
    void swaggerUiIsServed() throws Exception {

        assertEquals(200, get("/swagger-ui/index.html").statusCode());
        // The specification behind it, which the documentation tells developers to generate clients from.
        final HttpResponse<String> spec = get("/v3/api-docs");
        assertEquals(200, spec.statusCode());
        assertTrue(spec.body().contains("\"openapi\""), "the specification must be served");

    }

    private HttpResponse<String> get(final String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String head(final String body) {
        return body.substring(0, Math.min(200, body.length()));
    }

}
