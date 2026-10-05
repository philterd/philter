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
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy details, managed policies, and copying, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PolicyDetailsIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String POLICY = "{ \"identifiers\": { \"ssn\": { \"ssnFilterStrategies\": [ { \"strategy\": \"REDACT\" } ] } } }";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "policy-details-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, "user", policyDataService, contextDataService, "test").isSuccessful());
        final ObjectId userId = userService.findByUsername(username).getId();
        key = apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String contentType, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject details(final String name) throws Exception {
        final HttpResponse<String> response = send("GET", "/api/policies/" + name + "/details", null, null);
        assertEquals(200, response.statusCode(), response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private static String q(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Description and notes are saved with the policy, kept when left out, and set or cleared by details")
    void descriptionAndNotes() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=court&description=" + q("Court filings")
                + "&notes=" + q("Line one\nline two"), "application/json", POLICY).statusCode());

        JsonObject details = details("court");
        assertEquals("Court filings", details.get("description").getAsString());
        assertEquals("Line one\nline two", details.get("notes").getAsString());
        assertFalse(details.get("managed").getAsBoolean());

        // Updating the policy without them keeps them.
        assertEquals(201, send("POST", "/api/policies?name=court", "application/json", POLICY).statusCode());
        assertEquals("Court filings", details("court").get("description").getAsString());

        // The policy JSON itself does not carry them.
        assertFalse(send("GET", "/api/policies/court", null, null).body().contains("Court filings"));

        // Details change without a new version; an empty value clears.
        final int revision = details("court").get("revision").getAsInt();
        final HttpResponse<String> set = send("PUT", "/api/policies/court/details", "application/json",
                "{\"description\":\"Federal court filings\",\"notes\":\"\"}");
        assertEquals(200, set.statusCode(), set.body());
        details = details("court");
        assertEquals("Federal court filings", details.get("description").getAsString());
        assertEquals("", details.get("notes").getAsString());
        assertEquals(revision, details.get("revision").getAsInt(), "description and notes are not versioned");

        assertEquals(400, send("PUT", "/api/policies/court/details", "application/json",
                "{\"description\":\"" + "x".repeat(201) + "\"}").statusCode());
        assertEquals(400, send("POST", "/api/policies?name=court&notes=" + "y".repeat(1001), "application/json", POLICY).statusCode());
        assertEquals(404, send("PUT", "/api/policies/nope/details", "application/json", "{\"notes\":\"n\"}").statusCode());
    }

    @Test
    @DisplayName("Managed policies are listed and read by name, and cannot be changed")
    void managedPolicies() throws Exception {
        final HttpResponse<String> listed = send("GET", "/api/policies?managed=true", null, null);
        assertEquals(200, listed.statusCode(), listed.body());
        final JsonArray names = gson.fromJson(listed.body(), JsonArray.class);
        assertTrue(names.toString().contains("\"managed_common_pii\""), names.toString());

        final HttpResponse<String> policy = send("GET", "/api/policies/managed_common_pii", null, null);
        assertEquals(200, policy.statusCode());
        assertTrue(policy.body().contains("identifiers"), policy.body());

        final JsonObject details = details("managed_common_pii");
        assertTrue(details.get("managed").getAsBoolean());
        assertFalse(details.get("description").getAsString().isEmpty());

        assertEquals(409, send("PUT", "/api/policies/managed_common_pii/details", "application/json", "{\"notes\":\"n\"}").statusCode());
        assertEquals(400, send("GET", "/api/policies?managed=true&all_users=true", null, null).statusCode());
        // Not in the caller's own listing.
        assertFalse(send("GET", "/api/policies", null, null).body().contains("managed_"));
    }

    @Test
    @DisplayName("Copying a managed or own policy creates an independent policy with its own versions")
    void copies() throws Exception {
        final HttpResponse<String> fromManaged = send("POST", "/api/policies/managed_common_pii/copy?name=pii", null, null);
        assertEquals(201, fromManaged.statusCode(), fromManaged.body());
        JsonObject copy = gson.fromJson(fromManaged.body(), JsonObject.class);
        assertFalse(copy.get("managed").getAsBoolean());
        assertEquals("Created from managed policy managed_common_pii", copy.get("notes").getAsString());
        assertEquals(send("GET", "/api/policies/managed_common_pii", null, null).body(),
                send("GET", "/api/policies/pii", null, null).body(), "the copy has the same policy");
        final HttpResponse<String> versions = send("GET", "/api/policies/pii/versions", null, null);
        assertEquals(200, versions.statusCode(), versions.body());
        assertEquals(1, gson.fromJson(versions.body(), JsonArray.class).size(), "the copy starts its own history: " + versions.body());

        // The copy is the caller's own: editable, and copyable in turn, keeping its notes.
        assertEquals(200, send("PUT", "/api/policies/pii/details", "application/json", "{\"notes\":\"tuned\"}").statusCode());
        copy = gson.fromJson(send("POST", "/api/policies/pii/copy?name=pii-2", null, null).body(), JsonObject.class);
        assertEquals("tuned", copy.get("notes").getAsString());

        assertEquals(409, send("POST", "/api/policies/pii/copy?name=pii-2", null, null).statusCode());
        assertEquals(400, send("POST", "/api/policies/pii/copy?name=managed_mine", null, null).statusCode());
        assertEquals(404, send("POST", "/api/policies/nope/copy?name=x", null, null).statusCode());
    }

}
