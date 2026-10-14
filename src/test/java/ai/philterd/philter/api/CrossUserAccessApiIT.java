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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADMIN_CROSS_USER_ACCESS_ENABLED bounds what an administrator can do with another user's data: with it off, an
 * administrator can create only a user's first API key, not a key for a user who may already hold data; with it on,
 * every read of another user's resources through owner is audited as admin_cross_user_access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class CrossUserAccessApiIT {

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
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(false);
        adminKey = keyFor(seedUser("admin"));
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String username = "cross-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return username;
    }

    private String keyFor(final String username) {
        return apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String key, final String method, final String path, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mint(final String username) throws Exception {
        return send(adminKey, "POST", "/api/users/" + username + "/api-keys", "{\"scopes\":[\"policies:read\"]}");
    }

    /** The details of each admin_cross_user_access event naming this user. */
    private List<String> crossUserActionsOn(final String username) throws Exception {
        final String userId = userService.findByUsername(username).getId();
        final HttpResponse<String> audit = send(adminKey, "GET", "/api/audit?event=admin_cross_user_access&limit=100", null);
        assertEquals(200, audit.statusCode(), audit.body());
        final List<String> actions = new ArrayList<>();
        for (final var element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            if (event.has("associatedObject") && userId.equals(event.get("associatedObject").getAsString())) {
                actions.add(event.get("details").getAsString());
            }
        }
        return actions;
    }

    @Test
    @DisplayName("With cross-user access off, an administrator creates a new user's first key, and no other")
    void onlyTheFirstKeyWithoutCrossUserAccess() throws Exception {

        final String newcomer = seedUser("user");
        final HttpResponse<String> first = mint(newcomer);
        assertEquals(201, first.statusCode(), first.body());

        final HttpResponse<String> second = mint(newcomer);
        assertEquals(403, second.statusCode(), second.body());
        assertTrue(second.body().contains("ADMIN_CROSS_USER_ACCESS_ENABLED"), second.body());

        // A user whose only key was revoked has still had one.
        final String revoked = seedUser("user");
        final String revokedKey = keyFor(revoked);
        assertEquals(204, send(adminKey, "DELETE", "/api/api-keys/" + apiKeyDataService.findOneByApiKey(revokedKey).getId(),
                null).statusCode());
        assertEquals(403, mint(revoked).statusCode());

        // An administrator can always create a key for themselves this way.
        final String adminName = userService.findOneById(apiKeyDataService.findOneByApiKey(adminKey).getUserId()).getUsername();
        assertEquals(201, mint(adminName).statusCode());

        assertTrue(crossUserActionsOn(newcomer).isEmpty(), "provisioning a first key is not cross-user access");

    }

    @Test
    @DisplayName("With cross-user access on, a key for a user who has had one is created and audited")
    void aFurtherKeyWithCrossUserAccessIsAudited() throws Exception {

        final String user = seedUser("user");
        keyFor(user);
        AdminAccessConfig.setOverrideForTesting(true);

        final HttpResponse<String> created = mint(user);
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(crossUserActionsOn(user).contains("action: create API key with scopes [policies:read]"),
                crossUserActionsOn(user).toString());

    }

    @Test
    @DisplayName("Every read of another user's resources through owner is audited as cross-user access")
    void readsThroughOwnerAreAudited() throws Exception {

        final String user = seedUser("user");
        final String userKey = keyFor(user);
        assertEquals(201, send(userKey, "POST", "/api/policies?name=theirs", POLICY).statusCode());
        assertEquals(201, send(userKey, "POST", "/api/lists/terms", "[\"x\"]").statusCode());
        AdminAccessConfig.setOverrideForTesting(true);

        final String owner = "owner=" + user;
        final String document = UUID.randomUUID().toString();
        final List<String> paths = List.of(
                "/api/policies?" + owner,
                "/api/policies/theirs?" + owner,
                "/api/policies/theirs/details?" + owner,
                "/api/lists?" + owner,
                "/api/lists/terms?" + owner,
                "/api/documents?" + owner,
                "/api/documents/" + document + "/status?" + owner,
                "/api/documents/" + document + "?" + owner,
                "/api/ledger?" + owner,
                "/api/ledger/" + document + "?" + owner,
                "/api/ledger/" + document + "/valid?" + owner);
        for (final String path : paths) {
            send(adminKey, "GET", path, null);
        }

        final List<String> actions = crossUserActionsOn(user);
        for (final String expected : List.of("list policies", "read policy 'theirs'", "read details of policy 'theirs'",
                "list custom lists", "read custom list 'terms'", "list documents",
                "read status of document '" + document + "'", "download document '" + document + "'",
                "list ledger chains", "read ledger '" + document + "'", "validate ledger '" + document + "'")) {
            assertTrue(actions.contains("action: " + expected), "missing '" + expected + "' in " + actions);
        }

        // A user reading their own resources is not cross-user access.
        final String ownOnly = seedUser("user");
        assertEquals(200, send(keyFor(ownOnly), "GET", "/api/policies?owner=" + ownOnly, null).statusCode());
        assertFalse(crossUserActionsOn(ownOnly).stream().anyMatch(a -> a.contains("list policies")));

    }

}
