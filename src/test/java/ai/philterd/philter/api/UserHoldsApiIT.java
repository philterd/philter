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
import ai.philterd.philter.config.LedgerDeletionConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.LedgerDataService;
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

/** A user hold, created as documented, covers all of its owner's ledger evidence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class UserHoldsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private LedgerDataService ledgerDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String username;
    private ObjectId userId;
    private String userKey;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        LedgerDeletionConfig.setOverrideForTesting(true);
        username = seedUser("user");
        userId = userService.findByUsername(username).getId();
        userKey = keyFor(username);
        adminKey = keyFor(seedUser("admin"));
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        LedgerDeletionConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String name = "holds-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", name, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return name;
    }

    private String keyFor(final String name) {
        return apiKeyDataService.createApiKey("req", userService.findByUsername(name).getId(), "test",
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

    private String completedChain() throws Exception {
        final String documentId = "doc-" + UUID.randomUUID();
        ledgerDataService.initializeLedger(userId, documentId, "input-hash", "note.txt", "default", 1, "policy-hash");
        ledgerDataService.completeChain(userId, documentId);
        return documentId;
    }

    @Test
    @DisplayName("A user hold without scopeValue covers all of its owner's chains, blocking delete and purge")
    void blocksDeletingAndPurging() throws Exception {
        final String first = completedChain();
        final String second = completedChain();
        final String owner = "owner=" + username;

        final HttpResponse<String> set = send(adminKey, "POST", "/api/holds?" + owner,
                "{\"reference\":\"leaver-review\",\"scopeType\":\"user\"}");
        assertEquals(201, set.statusCode(), set.body());
        assertEquals(username, gson.fromJson(set.body(), JsonObject.class).get("scopeValue").getAsString(),
                "the owner's username is stored");

        for (final String documentId : new String[]{first, second}) {
            assertEquals(423, send(adminKey, "DELETE", "/api/ledger/" + documentId + "?" + owner, null).statusCode(), documentId);
        }
        assertEquals(423, send(adminKey, "DELETE", "/api/ledger?older_than_days=0&" + owner, null).statusCode());

        assertEquals(200, send(adminKey, "DELETE", "/api/holds/leaver-review?" + owner, null).statusCode());
        final HttpResponse<String> deleted = send(adminKey, "DELETE", "/api/ledger/" + first + "?" + owner, null);
        assertTrue(deleted.statusCode() / 100 == 2, "with the hold released the chain can be deleted: " + deleted.body());
    }

    @Test
    @DisplayName("A user's own user hold stores their username, and a scopeValue naming them is accepted")
    void ownHoldAndMatchingScopeValue() throws Exception {
        final HttpResponse<String> own = send(userKey, "POST", "/api/holds", "{\"reference\":\"mine\",\"scopeType\":\"user\"}");
        assertEquals(201, own.statusCode(), own.body());
        assertEquals(username, gson.fromJson(own.body(), JsonObject.class).get("scopeValue").getAsString());

        final HttpResponse<String> named = send(userKey, "POST", "/api/holds",
                "{\"reference\":\"named\",\"scopeType\":\"user\",\"scopeValue\":\"" + username + "\"}");
        assertEquals(201, named.statusCode(), named.body());
    }

    @Test
    @DisplayName("A scopeValue that is not the owner's username is refused, and a document hold still needs one")
    void refusals() throws Exception {
        for (final String value : new String[]{"all", userId.toHexString(), "someone-else"}) {
            final HttpResponse<String> refused = send(userKey, "POST", "/api/holds",
                    "{\"reference\":\"r-" + UUID.randomUUID() + "\",\"scopeType\":\"user\",\"scopeValue\":\"" + value + "\"}");
            assertEquals(400, refused.statusCode(), value + ": " + refused.body());
            assertTrue(refused.body().contains("username"), refused.body());
        }

        // An administrator naming the user with owner must give that user's username, not their own.
        final HttpResponse<String> adminSelf = send(adminKey, "POST", "/api/holds?owner=" + username,
                "{\"reference\":\"x\",\"scopeType\":\"user\",\"scopeValue\":\"not-" + username + "\"}");
        assertEquals(400, adminSelf.statusCode(), adminSelf.body());

        assertEquals(400, send(userKey, "POST", "/api/holds",
                "{\"reference\":\"doc\",\"scopeType\":\"document_chain\"}").statusCode());

        // A mistyped scope type is reported as that, not as a missing scopeValue.
        final HttpResponse<String> typo = send(userKey, "POST", "/api/holds", "{\"reference\":\"t\",\"scopeType\":\"User\"}");
        assertEquals(400, typo.statusCode(), typo.body());
        assertTrue(typo.body().contains("Invalid scope type"), typo.body());
    }

}
