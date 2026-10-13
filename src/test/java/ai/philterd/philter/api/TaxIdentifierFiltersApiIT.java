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
 * The taxpayer and insurance number filters, through Philter's API: SSNs, ITINs, EINs, and Canadian SINs are each
 * reported as their own type, as the filter pages in the documentation describe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class TaxIdentifierFiltersApiIT {

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
        final String username = "tax-ids-" + UUID.randomUUID() + "@example.com";
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
    @DisplayName("SSNs, ITINs, EINs, and Canadian SINs are each reported as their own type")
    void eachIdentifierIsReportedAsItsOwnType() throws Exception {
        savePolicy("tax-ids", "\"ssn\":{\"ssnFilterStrategies\":" + REDACT + "},"
                + "\"itin\":{\"itinFilterStrategies\":" + REDACT + "},"
                + "\"ein\":{\"einFilterStrategies\":" + REDACT + "},"
                + "\"canadaSin\":{\"canadaSinFilterStrategies\":" + REDACT + "}");

        assertEquals("SSN {{{REDACTED-ssn}}}, ITIN {{{REDACTED-itin}}}, EIN {{{REDACTED-ein}}}, SIN {{{REDACTED-canada-sin}}}.",
                redact("tax-ids", "SSN 123-45-6789, ITIN 912-70-1234, EIN 12-3456789, SIN 046-454-286."));
        // Only the SSN filter accepts other hyphens, such as U+2010, so such a value is reported as an SSN.
        assertEquals("EIN {{{REDACTED-ssn}}}.", redact("tax-ids", "EIN 12\u20103456789."));
    }

    @Test
    @DisplayName("The SSN filter does not report ITINs, and reports EIN-shaped values unless the EIN filter is on")
    void theSsnFilterAlone() throws Exception {
        savePolicy("ssn-only", "\"ssn\":{\"ssnFilterStrategies\":" + REDACT + "}");

        assertEquals("ITIN 912-70-1234, EIN {{{REDACTED-ssn}}}.", redact("ssn-only", "ITIN 912-70-1234, EIN 12-3456789."));
    }

    @Test
    @DisplayName("The ITIN filter's issued ranges and the SIN filter's Luhn check and prefixes narrow what is reported")
    void theNarrowingOptions() throws Exception {
        savePolicy("narrowed", "\"itin\":{\"onlyValidRanges\":true,\"itinFilterStrategies\":" + REDACT + "},"
                + "\"canadaSin\":{\"onlyValidPrefixes\":true,\"canadaSinFilterStrategies\":" + REDACT + "}");

        // 912-93-1234 is an ATIN, outside the issued ITIN ranges. 130-692-545 fails the SIN's Luhn check; 812-345-676
        // and 046-454-286 pass it, but begin with 8 (a business number) and 0, which are not issued as personal SINs.
        assertEquals("{{{REDACTED-itin}}} 912-93-1234 {{{REDACTED-canada-sin}}} 130-692-545 812-345-676 046-454-286",
                redact("narrowed", "912-70-1234 912-93-1234 130-692-544 130-692-545 812-345-676 046-454-286"));
    }

}
