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
 * The currency and street address filters, through Philter's API: what each detects and what it does not, as their
 * filter pages in the documentation describe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class CurrencyAndStreetAddressFiltersApiIT {

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
        final String username = "currency-address-" + UUID.randomUUID() + "@example.com";
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
    @DisplayName("Amounts with a currency symbol or code are reported; amounts without one are not")
    void currency() throws Exception {
        savePolicy("currency", "\"currency\":{\"currencyFilterStrategies\":" + REDACT + "}");

        assertEquals("{{{REDACTED-currency}}}, {{{REDACTED-currency}}}, {{{REDACTED-currency}}}, {{{REDACTED-currency}}}, "
                        + "{{{REDACTED-currency}}}, {{{REDACTED-currency}}}.",
                redact("currency", "$35.53, \u20ac1.450,00, 250,50 \u00a3, USD 1,234.56, 5000 JPY, 5 eur."));
        assertEquals("Paid {{{REDACTED-currency}}} today.", redact("currency", "Paid $3,450.75 USD today."),
                "a symbol and a code together are one amount");
        assertEquals("About 1,450 dollars, 100 CHF, or 100.", redact("currency", "About 1,450 dollars, 100 CHF, or 100."));
    }

    @Test
    @DisplayName("The street line of an address and PO boxes are reported, not the city, state, or ZIP code")
    void streetAddress() throws Exception {
        savePolicy("address", "\"streetAddress\":{\"streetAddressFilterStrategies\":" + REDACT + "}");

        assertEquals("Send it to {{{REDACTED-street-address}}}, Springfield, IL 62701",
                redact("address", "Send it to 123 Main Street, Suite 100, Springfield, IL 62701"));
        assertEquals("{{{REDACTED-street-address}}}, {{{REDACTED-street-address}}}, {{{REDACTED-street-address}}}, "
                        + "{{{REDACTED-street-address}}}, {{{REDACTED-street-address}}}",
                redact("address", "1600 Pennsylvania Avenue NW, 12B Elm St. Apt 4, PO Box 1234, P.O. Box 56, Post Office Box 789"));
        assertEquals("42 Broadway, Hauptstrasse 5, RR 2 Box 15", redact("address", "42 Broadway, Hauptstrasse 5, RR 2 Box 15"),
                "addresses without a street type, in other formats, or on rural routes are not reported");
        // As the page warns: a street type is an ordinary word here, and the period after it is part of the span.
        assertEquals("Walked {{{REDACTED-street-address}}}", redact("address", "Walked 5 miles down the path."));
    }

}
