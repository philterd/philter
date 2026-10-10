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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting a policy over real HTTP: a refusal is reported, not answered with 200. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PoliciesApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String POLICY = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId userId;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "policies-" + UUID.randomUUID();
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

    private HttpResponse<String> send(final String method, final String path, final String body) throws Exception {
        return sendAs(key, method, path, body);
    }

    private HttpResponse<String> sendAs(final String apiKey, final String method, final String path, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(final HttpResponse<String> response) {
        return gson.fromJson(response.body(), JsonObject.class);
    }

    @Test
    @DisplayName("Deleting a policy that does not exist returns 404 with a message")
    void deleteMissing() throws Exception {
        final HttpResponse<String> refused = send("DELETE", "/api/policies/missing", null);
        assertEquals(404, refused.statusCode(), refused.body());
        assertEquals("Policy does not exist.", json(refused).get("message").getAsString());
    }

    @Test
    @DisplayName("Deleting the default policy returns 409 with a reason, and the policy is kept")
    void deleteDefault() throws Exception {
        final HttpResponse<String> refused = send("DELETE", "/api/policies/default", null);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("Cannot delete the default policy.", json(refused).get("message").getAsString());
        assertEquals("policy_default", json(refused).get("reason").getAsString());
        assertEquals(200, send("GET", "/api/policies/default", null).statusCode(), "the default policy is kept");
    }

    @Test
    @DisplayName("Deleting a policy returns 200 and the policy is gone")
    void deleteSucceeds() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=temporary", POLICY).statusCode());

        final HttpResponse<String> deleted = send("DELETE", "/api/policies/temporary", null);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertEquals(404, send("GET", "/api/policies/temporary", null).statusCode());
    }

    @Test
    @DisplayName("Creating a policy whose name is taken is refused with policy_exists and changes nothing")
    void createRefusesATakenName() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=taken", POLICY).statusCode());
        final String before = send("GET", "/api/policies/taken", null).body();

        final HttpResponse<String> refused = send("POST", "/api/policies?name=taken", POLICY.replace("REDACT", "MASK"));
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("policy_exists", json(refused).get("reason").getAsString());
        assertEquals(before, send("GET", "/api/policies/taken", null).body(), "the policy is unchanged");

        // The default policy every user has is refused the same way.
        assertEquals(409, send("POST", "/api/policies?name=default", POLICY).statusCode());
    }

    @Test
    @DisplayName("The default template is accepted as a new policy")
    void theDefaultTemplateSavesAsAPolicy() throws Exception {
        final HttpResponse<String> template = send("GET", "/api/policies/templates/default", null);
        assertEquals(200, template.statusCode(), template.body());
        assertTrue(template.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(json(template).has("identifiers"), "the template is native policy JSON");

        final HttpResponse<String> saved = send("POST", "/api/policies?name=from-template", template.body());
        assertEquals(201, saved.statusCode(), saved.body());
        assertEquals(json(template), json(send("GET", "/api/policies/from-template", null)));
    }

    @Test
    @DisplayName("An unknown template is not found, and the message names the templates there are")
    void anUnknownTemplateIsNotFound() throws Exception {
        final HttpResponse<String> missing = send("GET", "/api/policies/templates/missing", null);
        assertEquals(404, missing.statusCode(), missing.body());
        assertTrue(json(missing).get("message").getAsString().contains("default"), missing.body());
    }

    @Test
    @DisplayName("templates cannot be a policy name, since its path is where templates are read")
    void templatesIsAReservedPolicyName() throws Exception {
        final HttpResponse<String> created = send("POST", "/api/policies?name=templates", POLICY);
        assertEquals(400, created.statusCode(), created.body());
        assertTrue(json(created).get("message").getAsString().contains("reserved"), created.body());

        final HttpResponse<String> copied = send("POST", "/api/policies/default/copy?name=templates", null);
        assertEquals(400, copied.statusCode(), copied.body());

        assertEquals(404, send("GET", "/api/policies/templates/details", null).statusCode(),
                "no policy can be named templates, so this is a template lookup");
    }

    @Test
    @DisplayName("Replacing a policy stores a new revision and keeps an omitted description")
    void replaceStoresANewRevision() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=replaced", POLICY).statusCode());
        assertEquals(200, send("PUT", "/api/policies/replaced/details", "{\"description\":\"Kept\"}").statusCode());
        final int before = json(send("GET", "/api/policies/replaced/details", null)).get("revision").getAsInt();

        final HttpResponse<String> replaced = send("PUT", "/api/policies/replaced", POLICY.replace("REDACT", "MASK"));
        assertEquals(200, replaced.statusCode(), replaced.body());

        assertTrue(send("GET", "/api/policies/replaced", null).body().contains("MASK"));
        final JsonObject details = json(send("GET", "/api/policies/replaced/details", null));
        assertTrue(details.get("revision").getAsInt() > before, details.toString());
        assertEquals("Kept", details.get("description").getAsString());
    }

    @Test
    @DisplayName("Replacing a policy that does not exist returns 404 and creates nothing")
    void replaceRefusesAMissingPolicy() throws Exception {
        final HttpResponse<String> refused = send("PUT", "/api/policies/missing", POLICY);
        assertEquals(404, refused.statusCode(), refused.body());
        assertEquals("Policy does not exist.", json(refused).get("message").getAsString());
        assertEquals(404, send("GET", "/api/policies/missing", null).statusCode(), "nothing was created");
    }

    @Test
    @DisplayName("Replacing with an invalid policy returns 400 and keeps the policy")
    void replaceValidates() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=validated", POLICY).statusCode());
        assertEquals(400, send("PUT", "/api/policies/validated", "{\"identifiers\":{}}").statusCode());
        assertTrue(send("GET", "/api/policies/validated", null).body().contains("REDACT"));
    }

    @Test
    @DisplayName("An administrator creates and replaces another user's policy with owner")
    void administratorCreatesAndReplaces() throws Exception {
        AdminAccessConfig.setOverrideForTesting(true);
        try {
            final String adminName = "policies-admin-" + UUID.randomUUID();
            assertTrue(userService.createUser("req", adminName, null, "admin", policyDataService, contextDataService, "test").isSuccessful());
            final String adminKey = apiKeyDataService.createApiKey("req", userService.findByUsername(adminName).getId(), "test",
                    ApiKeyScope.all()).getMessage();
            final String owner = "owner=" + userService.findOneById(userId).getUsername();

            assertEquals(201, sendAs(adminKey, "POST", "/api/policies?name=by-admin&" + owner, POLICY).statusCode());
            assertEquals(409, sendAs(adminKey, "POST", "/api/policies?name=by-admin&" + owner, POLICY).statusCode());
            assertEquals(200, sendAs(adminKey, "PUT", "/api/policies/by-admin?" + owner, POLICY.replace("REDACT", "MASK")).statusCode());

            // The policy is the user's, not the administrator's.
            assertTrue(send("GET", "/api/policies/by-admin", null).body().contains("MASK"));
            assertEquals(404, sendAs(adminKey, "GET", "/api/policies/by-admin", null).statusCode());
        } finally {
            AdminAccessConfig.setOverrideForTesting(null);
        }
    }

    @Test
    @DisplayName("A rollback 404 says whether the policy or the revision does not exist")
    void rollbackNotFoundSaysWhich() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=rolled", POLICY).statusCode());

        final HttpResponse<String> noRevision = send("POST", "/api/policies/rolled/rollback?revision=99", null);
        assertEquals(404, noRevision.statusCode(), noRevision.body());
        assertEquals("Revision 99 does not exist.", json(noRevision).get("message").getAsString());

        final HttpResponse<String> noPolicy = send("POST", "/api/policies/missing/rollback?revision=0", null);
        assertEquals(404, noPolicy.statusCode(), noPolicy.body());
        assertEquals("Policy does not exist.", json(noPolicy).get("message").getAsString());
    }

}
