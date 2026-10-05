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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** all_users over real HTTP, against stored resources belonging to two users. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class AllUsersListingsIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private CustomListDataService customListDataService;
    @Autowired private LedgerDataService ledgerDataService;
    @Autowired private LegalHoldDataService legalHoldDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;
    private ObjectId adminId;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);

        adminId = userService.findByUsername(seedUser("admin")).getId();
        adminKey = seedKey(adminId);

        // Two users, each with a policy and context named "default" (made at creation), a custom list,
        // a ledger chain, and a legal hold.
        alice = seedUser("user");
        bob = seedUser("user");
        for (final String username : List.of(alice, bob)) {
            final ObjectId id = userService.findByUsername(username).getId();
            assertTrue(customListDataService.saveOrUpdate("req", id, "names-" + username, "d", List.of("x"), true, "test").isSuccessful());
            ledgerDataService.initializeLedger(id, "doc-" + username, "hash", username + ".txt", "default", 0, "policy-hash");
            assertTrue(legalHoldDataService.create("req", "hold-" + username, "document_chain", "doc-" + username,
                    "litigation", id, adminId).isSuccessful());
        }
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String username = "all-users-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return username;
    }

    private String seedKey(final ObjectId userId) {
        return apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> get(final String path, final String key) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Every item across all pages, as "name|owner", asserting no item repeats. */
    private Set<String> allPages(final String path, final String arrayField, final String nameField) throws Exception {
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; ; offset += 7) {
            final HttpResponse<String> page = get(path + (path.contains("?") ? "&" : "?") + "all_users=true&limit=7&offset=" + offset, adminKey);
            assertEquals(200, page.statusCode(), page.body());
            final JsonElement parsed = gson.fromJson(page.body(), JsonElement.class);
            final JsonArray items = arrayField == null ? parsed.getAsJsonArray() : parsed.getAsJsonObject().getAsJsonArray(arrayField);
            for (final JsonElement item : items) {
                final JsonObject object = item.getAsJsonObject();
                assertTrue(seen.add(object.get(nameField).getAsString() + "|" + object.get("owner").getAsString()),
                        "an item repeated across pages: " + object);
            }
            if (items.size() < 7) {
                return seen;
            }
        }
    }

    @Test
    @DisplayName("Each listing returns every user's resources, naming the owner, with stable paging")
    void listsAcrossUsers() throws Exception {

        final Set<String> policies = allPages("/api/policies", null, "name");
        assertTrue(policies.containsAll(Set.of("default|" + alice, "default|" + bob)), policies.toString());
        assertEquals(policyDataService.countAllAcrossUsers(false), policies.size(),
                "paging through every user's policies returns each exactly once");

        final Set<String> contexts = allPages("/api/contexts", "contexts", "name");
        assertTrue(contexts.containsAll(Set.of("default|" + alice, "default|" + bob)), contexts.toString());

        final Set<String> lists = allPages("/api/lists", null, "name");
        assertTrue(lists.containsAll(Set.of("names-" + alice + "|" + alice, "names-" + bob + "|" + bob)), lists.toString());

        final Set<String> chains = allPages("/api/ledger", "chains", "documentId");
        assertTrue(chains.containsAll(Set.of("doc-" + alice + "|" + alice, "doc-" + bob + "|" + bob)), chains.toString());

        final Set<String> holds = allPages("/api/holds", null, "reference");
        assertTrue(holds.containsAll(Set.of("hold-" + alice + "|" + alice, "hold-" + bob + "|" + bob)), holds.toString());

    }

    @Test
    @DisplayName("Managed policies are not listed, as they are not in the per-user listing")
    void leavesOutManagedPolicies() throws Exception {
        final PolicyEntity managed = new PolicyEntity();
        managed.setName("managed_all_users_" + UUID.randomUUID());
        managed.setManaged(true);
        managed.setPolicy("{}");
        policyDataService.save(managed);

        assertFalse(allPages("/api/policies", null, "name").stream().anyMatch(item -> item.startsWith(managed.getName())));
    }

    @Test
    @DisplayName("Per-user listings are unchanged: names only, no owner")
    void perUserListingsAreUnchanged() throws Exception {
        final String aliceKey = seedKey(userService.findByUsername(alice).getId());
        assertEquals("[\"default\"]", get("/api/policies", aliceKey).body());
        assertEquals("{\"contexts\":[\"default\"]}", get("/api/contexts", aliceKey).body());
        assertFalse(get("/api/holds", aliceKey).body().contains("owner"));
        assertFalse(get("/api/ledger", aliceKey).body().contains("owner"));
    }

    @Test
    @DisplayName("all_users is refused as owner is: 404 for a non-administrator or with cross-user access off")
    void refusals() throws Exception {
        final String aliceKey = seedKey(userService.findByUsername(alice).getId());
        for (final String path : List.of("/api/policies", "/api/contexts", "/api/lists", "/api/ledger", "/api/holds")) {
            assertEquals(404, get(path + "?all_users=true", aliceKey).statusCode(), path);
            AdminAccessConfig.setOverrideForTesting(false);
            assertEquals(404, get(path + "?all_users=true", adminKey).statusCode(), path);
            AdminAccessConfig.setOverrideForTesting(true);
            assertEquals(400, get(path + "?all_users=true&owner=" + alice, adminKey).statusCode(), path);
        }
        assertEquals(400, get("/api/ledger?all_users=true&q=doc", adminKey).statusCode());
    }

    @Test
    @DisplayName("Listing across users is audited")
    void isAudited() throws Exception {
        assertEquals(200, get("/api/holds?all_users=true", adminKey).statusCode());

        final HttpResponse<String> audit = get("/api/audit?event=admin_cross_user_access&limit=100", adminKey);
        assertTrue(audit.body().contains("action: list legal holds across all users"), audit.body());
    }

}
