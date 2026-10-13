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
 * Filter options the filter pages document, through Philter's API: the dictionary filters' {@code terms} and
 * {@code capitalized}, the tracking number filter's {@code allowSpaces}, and the {@code ABBREVIATE} strategy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class FilterOptionsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String REDACT = "[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{REDACTED-%t}}}\"}]";

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
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        final String username = "options-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "admin", policyDataService,
                contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        apiKey = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private HttpRequest.Builder authenticated(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + apiKey);
    }

    private void savePolicy(final String name, final String identifiers) throws Exception {
        final HttpResponse<String> saved = httpClient.send(authenticated("/api/policies?name=" + name)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identifiers\":{" + identifiers + "}}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
    }

    private String redact(final String policy, final String text) throws Exception {
        final HttpResponse<String> redacted = httpClient.send(authenticated("/api/filter?p=" + policy)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(text)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, redacted.statusCode(), redacted.body());
        return redacted.body();
    }

    @Test
    @DisplayName("terms replaces a dictionary filter's bundled list")
    void termsReplaceTheBundledList() throws Exception {
        savePolicy("bundled", "\"city\":{\"cityFilterStrategies\":" + REDACT + "}");
        savePolicy("own-terms", "\"city\":{\"terms\":[\"Springfield\"],\"cityFilterStrategies\":" + REDACT + "}");

        assertEquals("From {{{REDACTED-city}}} to {{{REDACTED-city}}}.", redact("bundled", "From Springfield to Boston."));
        assertEquals("From {{{REDACTED-city}}} to Boston.", redact("own-terms", "From Springfield to Boston."),
                "only the given terms are detected");
        savePolicy("empty-terms", "\"city\":{\"terms\":[],\"cityFilterStrategies\":" + REDACT + "}");
        assertEquals("From {{{REDACTED-city}}} to {{{REDACTED-city}}}.", redact("empty-terms", "From Springfield to Boston."),
                "an empty list uses the bundled list");
    }

    @Test
    @DisplayName("capitalized leaves a lowercase term unreported with fuzzy matching, and has no effect without it")
    void capitalizedAppliesOnlyWithFuzzyMatching() throws Exception {
        savePolicy("capitalized-fuzzy", "\"city\":{\"terms\":[\"Mobile\"],\"fuzzy\":true,\"capitalized\":true,"
                + "\"cityFilterStrategies\":" + REDACT + "}");
        savePolicy("capitalized-exact", "\"city\":{\"terms\":[\"Mobile\"],\"capitalized\":true,"
                + "\"cityFilterStrategies\":" + REDACT + "}");

        assertEquals("{{{REDACTED-city}}} is in Alabama. My mobile phone.",
                redact("capitalized-fuzzy", "Mobile is in Alabama. My mobile phone."));
        assertEquals("{{{REDACTED-city}}} is in Alabama. My {{{REDACTED-city}}} phone.",
                redact("capitalized-exact", "Mobile is in Alabama. My mobile phone."));
    }

    @Test
    @DisplayName("allowSpaces detects tracking numbers written with spaces, and grouped USPS numbers are detected without it")
    void allowSpaces() throws Exception {
        savePolicy("tracking", "\"trackingNumber\":{\"trackingNumberFilterStrategies\":" + REDACT + "}");
        savePolicy("tracking-spaces", "\"trackingNumber\":{\"allowSpaces\":true,\"trackingNumberFilterStrategies\":" + REDACT + "}");
        final String text = "UPS 1Z999AA10123456784, spaced 1Z 999 AA1 0123 4567 84, digits 1234 5678 9012, "
                + "USPS 9400 1000 0000 0000 0000.";

        assertEquals("UPS {{{REDACTED-tracking-number}}}, spaced 1Z 999 AA1 0123 4567 84, digits 1234 5678 9012, "
                + "USPS {{{REDACTED-tracking-number}}}.", redact("tracking", text));
        // Spaced runs of digits of a tracking number's length match too, as the page warns.
        assertEquals("UPS {{{REDACTED-tracking-number}}}, spaced {{{REDACTED-tracking-number}}}, digits "
                + "{{{REDACTED-tracking-number}}}, USPS {{{REDACTED-tracking-number}}}.", redact("tracking-spaces", text));
    }

    @Test
    @DisplayName("ABBREVIATE replaces a name with its initials")
    void abbreviate() throws Exception {
        savePolicy("abbreviate", "\"firstName\":{\"firstNameFilterStrategies\":[{\"strategy\":\"ABBREVIATE\"}]}");
        assertEquals("J and M went home.", redact("abbreviate", "John and Mary went home."));
    }

}
