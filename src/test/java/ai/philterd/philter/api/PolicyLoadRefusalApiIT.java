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

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
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
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A policy Phileas refuses to load is refused with 400 and Phileas's reason when it is saved, replaced, copied, or
 * rolled back to, and when a stored one is used for redaction, rather than being saved and failing every redaction
 * with 500. These reach storage only through the API, so they run against every storage implementation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PolicyLoadRefusalApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String VALID = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    /** Each policy Phileas refuses, with the start of Phileas's reason. */
    private static final Map<String, String[]> REFUSED = Map.of(
            "sensitivity", new String[]{"{\"identifiers\":{\"city\":{\"fuzzy\":true,\"sensitivity\":\"meduim\"}}}",
                    "The sensitivity 'meduim' of the city filter is not valid."},
            "malformed", new String[]{"{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"condition\":\"token == SECRET9\",\"strategy\":\"REDACT\"}]}}}",
                    "Strategy 1 of the ssn filter has a condition that is not valid"},
            "unsupported", new String[]{"{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"condition\":"
                    + "\"token == \\\"SECRET9\\\" and context startswith \\\"x\\\"\",\"strategy\":\"REDACT\"}]}}}",
                    "Strategy 1 of the ssn filter has a condition that is not supported: context supports == and !=, not startswith."},
            "regex", new String[]{"{\"identifiers\":{\"identifiers\":[{\"pattern\":\"([a-z\",\"identifierFilterStrategies\":[{\"strategy\":\"REDACT\"}]}]}}",
                    "The identifier 'custom-identifier' pattern is not a valid regular expression"});

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private PolicyVersionDataService policyVersionDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String userId;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "load-refusal-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        userId = userService.findByUsername(username).getId();
        key = apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String contentType, final String body) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Submits a one-page PDF for asynchronous redaction. */
    private HttpResponse<String> sendPdf(final String path) throws Exception {
        try (final org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument();
             final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(out);
            return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private String message(final HttpResponse<String> response) {
        return gson.fromJson(response.body(), JsonObject.class).get("message").getAsString();
    }

    private void assertRefused(final HttpResponse<String> response, final String reason) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(message(response).contains(reason), response.body());
        assertFalse(response.body().contains("SECRET9"), "a condition's value is never repeated: " + response.body());
    }

    /** Stores a policy as a release of Philter before this check would have, bypassing validation. */
    private void storeUnchecked(final String name, final String policyJson, final int revision) {
        final PolicyEntity entity = new PolicyEntity();
        entity.setName(name);
        entity.setUserId(userId);
        entity.setPolicy(policyJson);
        entity.setRevision(revision);
        entity.setCreatedTimestamp(new Date());
        entity.setLastUpdatedTimestamp(new Date());
        policyDataService.save(entity);
        policyVersionDataService.snapshot(entity);
    }

    @Test
    @DisplayName("Saving a policy Phileas refuses returns 400 with Phileas's reason, and nothing is stored")
    void savingARefusedPolicyIsRefused() throws Exception {
        for (final Map.Entry<String, String[]> refused : REFUSED.entrySet()) {
            assertRefused(send("POST", "/api/policies?name=" + refused.getKey(), "application/json", refused.getValue()[0]),
                    refused.getValue()[1]);
            assertEquals(404, send("GET", "/api/policies/" + refused.getKey(), null, null).statusCode(), refused.getKey());
        }
    }

    @Test
    @DisplayName("Replacing a policy with one Phileas refuses returns 400 and keeps the original")
    void replacingWithARefusedPolicyIsRefused() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=kept", "application/json", VALID).statusCode());
        for (final String[] refused : REFUSED.values()) {
            assertRefused(send("PUT", "/api/policies/kept", "application/json", refused[0]), refused[1]);
        }
        assertEquals(gson.fromJson(VALID, JsonObject.class),
                gson.fromJson(send("GET", "/api/policies/kept", null, null).body(), JsonObject.class));
    }

    @Test
    @DisplayName("A stored policy Phileas refuses fails redaction, asynchronous submission, and copying with 400, not 500")
    void aStoredRefusedPolicyIsRefusedWhenUsed() throws Exception {
        for (final Map.Entry<String, String[]> refused : REFUSED.entrySet()) {
            final String name = "stored-" + refused.getKey();
            storeUnchecked(name, refused.getValue()[0], 1);
            assertRefused(send("POST", "/api/filter?p=" + name, "text/plain", "SSN 123-45-6789"), refused.getValue()[1]);
            assertRefused(sendPdf("/api/filter?p=" + name), refused.getValue()[1]);
            assertRefused(send("POST", "/api/policies/" + name + "/copy?name=copy-" + refused.getKey(), null, null), refused.getValue()[1]);
        }
    }

    @Test
    @DisplayName("Rolling back to a revision Phileas refuses returns 400 and keeps the current policy")
    void rollingBackToARefusedRevisionIsRefused() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=rolled", "application/json", VALID).statusCode());
        final String[] refused = REFUSED.get("unsupported");
        final PolicyEntity old = policyDataService.findOne("rolled", userId);
        old.setPolicy(refused[0]);
        old.setRevision(old.getRevision() + 50);
        policyVersionDataService.snapshot(old);

        final HttpResponse<String> response = send("POST", "/api/policies/rolled/rollback?revision=" + old.getRevision(), null, null);
        assertRefused(response, refused[1]);
        assertTrue(message(response).startsWith("Revision " + old.getRevision() + " cannot be restored: "), response.body());
        assertEquals(gson.fromJson(VALID, JsonObject.class),
                gson.fromJson(send("GET", "/api/policies/rolled", null, null).body(), JsonObject.class));
    }

}
