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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ledger listings and export when an entry can no longer be read, as after a key change. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class UnreadableLedgerEntriesApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String SSN = "123-45-6789";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private LedgerDataService ledgerDataService;
    @Autowired private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId userId;
    private String username;
    private String key;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        username = seedUser("user");
        userId = userService.findByUsername(username).getId();
        key = keyFor(username);
        adminKey = keyFor(seedUser("admin"));
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String name = "ledger-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", name, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return name;
    }

    private String keyFor(final String name) {
        return apiKeyDataService.createApiKey("req", userService.findByUsername(name).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    /** A chain of a genesis entry and two redactions, as a redaction with the ledger on writes. */
    private String writeChain() throws Exception {
        final String documentId = "doc-" + UUID.randomUUID();
        ledgerDataService.initializeLedger(userId, documentId, "input-hash", "note.txt", "default", 1, "policy-hash");
        for (int i = 0; i < 2; i++) {
            final LedgerEntity entry = new LedgerEntity();
            entry.setUserId(userId);
            entry.setDocumentId(documentId);
            entry.setToken(SSN);
            entry.setReplacement("{{{REDACTED-ssn}}}");
            entry.setType("ssn");
            entry.setDocumentHash("hash-" + i);
            entry.setPreviousHash(ledgerDataService.getLatestTransaction(userId, documentId).getHash());
            entry.setTimestamp(new Date());
            entry.setFilename("note.txt");
            entry.setPolicyName("default");
            entry.setPolicyVersion(1);
            entry.setPolicyContentHash("policy-hash");
            entry.setHash(entry.calculateHash());
            ledgerDataService.addTransaction(entry);
        }
        return documentId;
    }

    /** Makes the chain's head, or only its later entries, unreadable, as the issue does. */
    private void damage(final String documentId, final boolean head) {
        mongoClient.getDatabase("philter").getCollection("ledger").updateMany(
                Filters.and(Filters.eq("document_id", documentId),
                        head ? Filters.eq("previous_hash", LedgerDataService.GENESIS)
                                : Filters.ne("previous_hash", LedgerDataService.GENESIS)),
                Updates.set("replacement_encrypted_key", "not-a-key"));
    }

    private HttpResponse<String> get(final String path, final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The listed chains by document id. */
    private Map<String, JsonObject> listed(final HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        final Map<String, JsonObject> chains = new HashMap<>();
        for (final JsonElement element : gson.fromJson(response.body(), JsonObject.class).getAsJsonArray("chains")) {
            chains.put(element.getAsJsonObject().get("documentId").getAsString(), element.getAsJsonObject());
        }
        return chains;
    }

    @Test
    @DisplayName("A chain whose head cannot be read is listed, marked, beside the readable ones")
    void unreadableHeadIsListed() throws Exception {
        final String good = writeChain();
        final String bad = writeChain();
        damage(bad, true);

        final HttpResponse<String> own = get("/api/ledger", key);
        final Map<String, JsonObject> chains = listed(own);
        assertEquals(2, gson.fromJson(own.body(), JsonObject.class).get("total").getAsInt(), "total is unchanged");
        assertFalse(chains.get(good).has("readError"), chains.get(good).toString());
        assertTrue(chains.get(bad).has("readError"), chains.get(bad).toString());
        assertFalse(chains.get(bad).has("replacement"), "the unreadable replacement is not shown");
        assertEquals("note.txt", chains.get(bad).get("filename").getAsString(), "the fields stored in the clear are");
        assertFalse(own.body().contains(SSN));

        final Map<String, JsonObject> across = listed(get("/api/ledger?all_users=true&limit=100", adminKey));
        assertTrue(across.get(bad).has("readError"), String.valueOf(across.get(bad)));
        assertEquals(username, across.get(bad).get("owner").getAsString());
        assertNotNull(across.get(good));
    }

    @Test
    @DisplayName("A chain whose later entry cannot be read lists normally, since only heads are listed")
    void unreadableLaterEntryListsNormally() throws Exception {
        final String bad = writeChain();
        damage(bad, false);

        assertFalse(listed(get("/api/ledger", key)).get(bad).has("readError"));
        assertFalse(listed(get("/api/ledger?all_users=true&limit=100", adminKey)).get(bad).has("readError"));
    }

    @Test
    @DisplayName("Exporting a chain with an unreadable entry is refused with entry_unreadable, and audited")
    void exportIsRefused() throws Exception {
        for (final boolean head : new boolean[]{true, false}) {
            final String bad = writeChain();
            damage(bad, head);

            final HttpResponse<String> refused = get("/api/ledger/" + bad + "/export", key);
            assertEquals(422, refused.statusCode(), refused.body());
            final JsonObject body = gson.fromJson(refused.body(), JsonObject.class);
            assertEquals("entry_unreadable", body.get("reason").getAsString());
            assertTrue(body.has("message"));
            assertFalse(refused.body().contains(SSN), "nothing from the chain is returned");

            final HttpResponse<String> audit = get("/api/audit?event=redaction_ledger_exported&limit=100", adminKey);
            assertTrue(audit.body().contains("documentId: " + bad + ", count: 0, refused: entry_unreadable"), audit.body());
        }

        // A readable chain still exports.
        assertEquals(200, get("/api/ledger/" + writeChain() + "/export", key).statusCode());
    }

    @Test
    @DisplayName("Listing a page with an unreadable chain is audited as a listing is")
    void listingIsAudited() throws Exception {
        damage(writeChain(), true);
        assertEquals(200, get("/api/ledger", key).statusCode());

        final HttpResponse<String> audit = get("/api/audit?event=redaction_ledger_query&owner=" + username + "&limit=100", adminKey);
        assertEquals(200, audit.statusCode(), audit.body());
        assertTrue(gson.fromJson(audit.body(), JsonObject.class).get("total").getAsInt() > 0, audit.body());
    }

}
