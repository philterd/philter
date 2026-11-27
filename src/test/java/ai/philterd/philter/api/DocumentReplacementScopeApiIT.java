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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default {@code DOCUMENT} replacement scope through Philter's API (issue #230): a repeated value gets one
 * replacement within a document, each document gets its own, and nothing is stored in the context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class DocumentReplacementScopeApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String SSN = "123-45-6789";

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

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        final String username = "document-scope-" + UUID.randomUUID() + "@example.com";
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
        final HttpResponse<String> redacted = httpClient.send(authenticated("/api/filter?p=" + policy + "&c=default")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(text)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, redacted.statusCode(), redacted.body());
        return redacted.body();
    }

    /** The two replacements in a redacted {@code "A <ssn> B <ssn> C"}. */
    private static List<String> replacements(final String redacted) {
        final Matcher matcher = Pattern.compile("^A (.+) B (.+) C$").matcher(redacted.trim());
        assertTrue(matcher.matches(), redacted);
        return List.of(matcher.group(1), matcher.group(2));
    }

    @Test
    @DisplayName("A repeated value gets one replacement in a document and a new one in the next document")
    void aRepeatedValueGetsOneReplacementPerDocument() throws Exception {
        savePolicy("random-ssn", "\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"RANDOM_REPLACE\"}]}");

        final List<String> first = replacements(redact("random-ssn", "A " + SSN + " B " + SSN + " C"));
        final List<String> second = replacements(redact("random-ssn", "A " + SSN + " B " + SSN + " C"));

        assertNotEquals(SSN, first.get(0));
        assertEquals(first.get(0), first.get(1), "both occurrences in one document share a replacement");
        assertEquals(second.get(0), second.get(1), "both occurrences in one document share a replacement");
        assertNotEquals(first.get(0), second.get(0), "each document in the context gets its own replacement");

        final HttpResponse<String> entries = httpClient.send(authenticated("/api/contexts/default/entries").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, entries.statusCode(), entries.body());
        assertEquals(0, gson.fromJson(entries.body(), JsonObject.class).getAsJsonArray("entries").size(),
                "DOCUMENT-scoped replacements are not stored in the context");
    }

    @Test
    @DisplayName("A random date shift moves every date in a document by the same number of days")
    void aRandomShiftIsOnePerDocument() throws Exception {
        savePolicy("random-shift", "\"date\":{\"dateFilterStrategies\":[{\"strategy\":\"SHIFT\",\"shiftRandom\":true}]}");

        final List<String> originals = List.of("03/27/2020", "11/02/2021", "06/30/2022");
        final String redacted = redact("random-shift", "Seen " + String.join(", then ", originals) + ".");

        final DateTimeFormatter format = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        final Matcher shifted = Pattern.compile("\\d{2}/\\d{2}/\\d{4}").matcher(redacted);
        final List<Long> shifts = new ArrayList<>();
        for (final String original : originals) {
            assertTrue(shifted.find(), redacted);
            shifts.add(ChronoUnit.DAYS.between(LocalDate.parse(original, format), LocalDate.parse(shifted.group(), format)));
        }
        assertNotEquals(0L, shifts.get(0), redacted);
        assertEquals(List.of(shifts.get(0), shifts.get(0), shifts.get(0)), shifts, redacted);
    }

}
