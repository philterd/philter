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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("Description and notes are set by details, kept when the policy is replaced, and cleared by details")
    void descriptionAndNotes() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=court", "application/json", POLICY).statusCode());
        assertEquals(200, send("PUT", "/api/policies/court/details", "application/json",
                gson.toJson(java.util.Map.of("description", "Court filings", "notes", "Line one\nline two"))).statusCode());

        JsonObject details = details("court");
        assertEquals("Court filings", details.get("description").getAsString());
        assertEquals("Line one\nline two", details.get("notes").getAsString());
        assertFalse(details.get("managed").getAsBoolean());

        // Replacing the policy without them keeps them.
        assertEquals(200, send("PUT", "/api/policies/court", "application/json", POLICY).statusCode());
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
        assertEquals(400, send("PUT", "/api/policies/court/details", "application/json",
                "{\"notes\":\"" + "y".repeat(1001) + "\"}").statusCode());
        assertEquals(404, send("PUT", "/api/policies/nope/details", "application/json", "{\"notes\":\"n\"}").statusCode());
    }

    @Test
    @DisplayName("Notes at their limit in a three-byte script survive creating and replacing a policy")
    void longNotesInAnyLanguage() throws Exception {
        // 1000 characters of three bytes each: 9000 bytes once percent-encoded, past Tomcat's 8 KB header
        // limit, so these could not travel in a query string.
        final String notes = "\u6f22".repeat(1000);
        final String description = "\u6f22".repeat(200);

        assertEquals(201, send("POST", "/api/policies?name=kanji", "application/json", POLICY).statusCode());
        final HttpResponse<String> set = send("PUT", "/api/policies/kanji/details", "application/json",
                gson.toJson(java.util.Map.of("description", description, "notes", notes)));
        assertEquals(200, set.statusCode(), set.body());

        assertEquals(200, send("PUT", "/api/policies/kanji", "application/json", POLICY.replace("REDACT", "MASK")).statusCode());

        final JsonObject details = details("kanji");
        assertEquals(notes, details.get("notes").getAsString());
        assertEquals(description, details.get("description").getAsString());
    }

    @Test
    @DisplayName("Creating or replacing a policy refuses description and notes, naming where they go")
    void createAndReplaceRefuseDetails() throws Exception {
        for (final String[] request : new String[][]{
                {"POST", "/api/policies?name=refused&description=" + q("Court filings")},
                {"POST", "/api/policies?name=refused&notes=n"}}) {
            final HttpResponse<String> refused = send(request[0], request[1], "application/json", POLICY);
            assertEquals(400, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("/details"), refused.body());
        }
        assertEquals(404, send("GET", "/api/policies/refused", null, null).statusCode(), "nothing was created");

        assertEquals(201, send("POST", "/api/policies?name=kept", "application/json", POLICY).statusCode());
        final HttpResponse<String> refused = send("PUT", "/api/policies/kept?notes=n", "application/json", POLICY.replace("REDACT", "MASK"));
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(send("GET", "/api/policies/kept", null, null).body().contains("MASK"), "the policy was not replaced");
    }

    @Test
    @DisplayName("Managed policies are listed and read by name, and cannot be changed")
    void managedPolicies() throws Exception {
        final HttpResponse<String> listed = send("GET", "/api/policies?managed=true", null, null);
        assertEquals(200, listed.statusCode(), listed.body());
        final JsonArray managed = gson.fromJson(listed.body(), JsonArray.class);
        assertFalse(managed.isEmpty(), listed.body());

        // Each entry carries its description, the same one the policy's details return.
        String listedDescription = null;
        for (final var element : managed) {
            final JsonObject entry = element.getAsJsonObject();
            assertTrue(entry.get("name").getAsString().startsWith("managed_"), entry.toString());
            assertEquals(details(entry.get("name").getAsString()).get("description").getAsString(),
                    entry.get("description").getAsString(), entry.toString());
            if ("managed_common_pii".equals(entry.get("name").getAsString())) {
                listedDescription = entry.get("description").getAsString();
            }
        }
        assertNotNull(listedDescription, "managed_common_pii is listed: " + listed.body());
        assertFalse(listedDescription.isEmpty());

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

        final HttpResponse<String> taken = send("POST", "/api/policies/pii/copy?name=pii-2", null, null);
        assertEquals(409, taken.statusCode(), taken.body());
        assertEquals("policy_exists", gson.fromJson(taken.body(), JsonObject.class).get("reason").getAsString(), taken.body());
        assertTrue(gson.fromJson(taken.body(), JsonObject.class).has("message"), taken.body());
        assertEquals(400, send("POST", "/api/policies/pii/copy?name=managed_mine", null, null).statusCode());
        assertEquals(404, send("POST", "/api/policies/nope/copy?name=x", null, null).statusCode());
    }

    @Test
    @DisplayName("A real managed policy is refused with policy_managed on rollback, replace, delete, and details, and is unchanged")
    void writesToAManagedPolicyAreRefused() throws Exception {
        final String before = send("GET", "/api/policies/managed_common_pii", null, null).body();
        final int revision = details("managed_common_pii").get("revision").getAsInt();

        final Map<String, HttpResponse<String>> refused = new java.util.LinkedHashMap<>();
        refused.put("rollback", send("POST", "/api/policies/managed_common_pii/rollback?revision=1", null, null));
        refused.put("replace", send("PUT", "/api/policies/managed_common_pii", "application/json", POLICY));
        refused.put("delete", send("DELETE", "/api/policies/managed_common_pii", null, null));
        refused.put("details", send("PUT", "/api/policies/managed_common_pii/details", "application/json", "{\"notes\":\"n\"}"));

        for (final Map.Entry<String, HttpResponse<String>> entry : refused.entrySet()) {
            final HttpResponse<String> response = entry.getValue();
            assertEquals(409, response.statusCode(), entry.getKey() + ": " + response.body());
            final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
            assertEquals("policy_managed", body.get("reason").getAsString(), entry.getKey() + ": " + response.body());
            assertTrue(body.has("message"), entry.getKey() + ": " + response.body());
        }
        assertEquals("Managed policies cannot be rolled back.",
                gson.fromJson(refused.get("rollback").body(), JsonObject.class).get("message").getAsString());

        assertEquals(before, send("GET", "/api/policies/managed_common_pii", null, null).body(), "the policy is unchanged");
        assertEquals(revision, details("managed_common_pii").get("revision").getAsInt(), "no version was recorded");

        // Nothing was activated: the audit log, read by an administrator, has no activation for it.
        final String admin = "managed-admin-" + java.util.UUID.randomUUID();
        assertTrue(userService.createUser("req", admin, null, "admin", policyDataService, contextDataService, "test").isSuccessful());
        final String adminKey = apiKeyDataService.createApiKey("req", userService.findByUsername(admin).getId(), "test",
                ApiKeyScope.all()).getMessage();
        final HttpResponse<String> audit = httpClient.send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/audit?event=policy_activated&limit=100"))
                .header("Authorization", "Bearer " + adminKey).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, audit.statusCode(), audit.body());
        for (final var event : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            // A copy made from it is recorded as "copied from"; only the policy itself being activated counts.
            assertFalse(event.getAsJsonObject().get("details").getAsString().startsWith("policy: managed_common_pii"),
                    event.toString());
        }
    }

}
