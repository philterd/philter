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
import ai.philterd.philter.data.mongodb.MongoIds;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import org.bson.Document;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code GET /api/pii-counts} over real HTTP against the whole application: the counts returned, the
 * filters and paging, the scope and admin checks, the audit of each read, and recording turned off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PiiCountsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final LocalDate FIRST = LocalDate.parse("2026-09-01");

    @Autowired
    private Environment environment;

    @Autowired
    private UserService userService;

    @Autowired
    private ApiKeyDataService apiKeyDataService;

    @Autowired
    private AdminSettingsDataService adminSettingsDataService;

    @Autowired
    private PolicyDataService policyDataService;

    @Autowired
    private ContextDataService contextDataService;

    @Autowired
    private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;
    private String adminUserId;

    /** Unique to each test, since the tests share a database. */
    private String claims;
    private String billing;

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        AdminAccessConfig.setOverrideForTesting(true);

        adminUserId = userId(createUser("counts-admin-", "admin"));
        adminKey = createKey(adminUserId, ApiKeyScope.all());

        claims = "claims-" + UUID.randomUUID();
        billing = "billing-" + UUID.randomUUID();
        for (int day = 0; day < 3; day++) {
            store(adminUserId, claims, FIRST.plusDays(day), new Document("SSN", 1 + day).append("PERSON", 5), 9);
            store(adminUserId, billing, FIRST.plusDays(day), new Document("SSN", 10 + day), 20 + day);
        }

    }

    @AfterEach
    void tearDown() {
        recording(false);
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    /** Stores counts as the recorder writes them. */
    private void store(final String userId, final String context, final LocalDate day, final Document counts,
                       final int totalDocuments) {
        final Date bucketStart = Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        mongoClient.getDatabase("philter").getCollection("pii_count_aggregates").insertOne(new Document("user_id",
                        new org.bson.types.ObjectId(userId)).append("context", context)
                .append("bucket_start", bucketStart).append("counts", counts).append("total_documents", totalDocuments)
                .append("updated_at", bucketStart));
    }

    private void recording(final boolean enabled) {
        adminSettingsDataService.update(new AdminSettingsDataService.Update(enabled, null, null, null, null, null,
                null, null, null, null), adminUserId, null);
    }

    /** Creates a user and returns its username. */
    private String createUser(final String prefix, final String role) {
        final String username = prefix + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return username;
    }

    private String userId(final String username) {
        return userService.findByUsername(username).getId();
    }

    private String createKey(final String userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private HttpResponse<String> get(final String query, final String apiKey) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pii-counts" + query)).GET();
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject read(final String query) throws Exception {
        final HttpResponse<String> response = get(query, adminKey);
        assertEquals(200, response.statusCode(), response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    /** "day context" for each entry. */
    private static List<String> keys(final JsonObject body) {
        final List<String> keys = new ArrayList<>();
        body.getAsJsonArray("counts").forEach(entry -> keys.add(entry.getAsJsonObject().get("day").getAsString()
                + " " + entry.getAsJsonObject().get("context").getAsString().split("-")[0]));
        return keys;
    }

    @Test
    @DisplayName("Returns the stored counts per context, day, and PII type, and only counts")
    void returnsTheStoredCounts() throws Exception {

        recording(true);
        final JsonObject body = read("?context=" + claims + "&from=2026-09-01&to=2026-09-01");

        assertTrue(body.get("recordingEnabled").getAsBoolean());
        assertEquals(1, body.get("total").getAsLong());
        final JsonArray counts = body.getAsJsonArray("counts");
        assertEquals(1, counts.size());
        final JsonObject entry = counts.get(0).getAsJsonObject();
        assertEquals(claims, entry.get("context").getAsString());
        assertEquals("2026-09-01", entry.get("day").getAsString());
        assertEquals(1, entry.getAsJsonObject("counts").get("SSN").getAsLong());
        assertEquals(5, entry.getAsJsonObject("counts").get("PERSON").getAsLong());
        assertEquals(9, entry.get("totalDocuments").getAsLong());
        assertNotNull(entry.get("updatedAt"));
        assertEquals(32, entry.get("id").getAsString().length());
        assertEquals(userService.findOneById(adminUserId).getUsername(), entry.get("owner").getAsString());
        assertEquals(Set.of("id", "owner", "context", "day", "counts", "totalDocuments", "updatedAt"), entry.keySet());

    }

    /** The entries in a listing for one day, keyed by id. */
    private Map<String, JsonObject> onDay(final JsonObject body, final String day) {
        final Map<String, JsonObject> entries = new java.util.HashMap<>();
        body.getAsJsonArray("counts").forEach(element -> {
            final JsonObject entry = element.getAsJsonObject();
            if (day.equals(entry.get("day").getAsString())) {
                entries.put(entry.get("id").getAsString(), entry);
            }
        });
        return entries;
    }

    @Test
    @DisplayName("Another user's counts are included but unnamed without cross-user access, and kept apart from yours")
    void anotherUsersCountsAreUnnamedWithoutCrossUserAccess() throws Exception {

        final String other = userId(createUser("counts-user-", "user"));
        final String shared = "default-" + UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2025-01-15");
        store(adminUserId, shared, day, new Document("SSN", 3), 3);
        store(other, shared, day, new Document("SSN", 5), 5);
        AdminAccessConfig.setOverrideForTesting(false);

        final Map<String, JsonObject> entries = onDay(read("?from=2025-01-15&to=2025-01-15&limit=100"), "2025-01-15");
        assertEquals(2, entries.size(), "two users' contexts of one name are separate entries, with separate ids");
        int unnamed = 0;
        for (final JsonObject entry : entries.values()) {
            if (entry.has("context")) {
                assertEquals(shared, entry.get("context").getAsString());
                assertEquals(3, entry.getAsJsonObject("counts").get("SSN").getAsLong(), "the caller's own");
            } else {
                unnamed++;
                assertFalse(entry.has("owner"), "another user's username is not shown");
                assertEquals(5, entry.getAsJsonObject("counts").get("SSN").getAsLong(), "but their counts are");
            }
        }
        assertEquals(1, unnamed);

        // Naming a context, or another user, does not reach their counts without cross-user access.
        assertEquals(1, read("?context=" + shared).get("total").getAsLong(), "only the caller's own");
        final String otherName = userService.findOneById(other).getUsername();
        assertEquals(404, get("?owner=" + otherName, adminKey).statusCode());

    }

    @Test
    @DisplayName("With cross-user access, another user's counts are named, and the listing is audited as cross-user access")
    void anotherUsersCountsAreNamedWithCrossUserAccess() throws Exception {

        final String other = userId(createUser("counts-user-", "user"));
        final String theirs = "theirs-" + UUID.randomUUID();
        store(other, theirs, LocalDate.parse("2025-02-01"), new Document("SSN", 2), 2);

        final JsonObject body = read("?context=" + theirs);
        assertEquals(1, body.get("total").getAsLong());
        final JsonObject entry = body.getAsJsonArray("counts").get(0).getAsJsonObject();
        assertEquals(theirs, entry.get("context").getAsString());
        assertEquals(userService.findOneById(other).getUsername(), entry.get("owner").getAsString());

        final HttpResponse<String> audit = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl
                        + "/api/audit?event=admin_cross_user_access&limit=200")).header("Authorization", "Bearer " + adminKey)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(audit.body().contains("list PII counts"), audit.body());

    }

    @Test
    @DisplayName("Filters by day range and context")
    void filtersByDaysAndContext() throws Exception {

        assertEquals(List.of("2026-09-03 claims", "2026-09-02 claims"),
                keys(read("?context=" + claims + "&from=2026-09-02")));
        assertEquals(List.of("2026-09-02 billing"), keys(read("?context=" + billing + "&from=2026-09-02&to=2026-09-02")));
        assertEquals(List.of("2026-09-01 billing"), keys(read("?context=" + billing + "&to=2026-09-01")));
        assertEquals(0, read("?context=none-" + UUID.randomUUID()).get("total").getAsLong());

    }

    @Test
    @DisplayName("Pages most recent day first, with the total")
    void pagesWithTheTotal() throws Exception {

        final List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 3; offset += 2) {
            final JsonObject page = read("?context=" + claims + "&offset=" + offset + "&limit=2");
            assertEquals(3, page.get("total").getAsLong());
            paged.addAll(keys(page));
        }
        assertEquals(List.of("2026-09-03 claims", "2026-09-02 claims", "2026-09-01 claims"), paged);

    }

    @Test
    @DisplayName("With recording off, says so, and is not an error")
    void recordingOff() throws Exception {

        recording(false);
        final JsonObject body = read("?context=none-" + UUID.randomUUID());

        assertFalse(body.get("recordingEnabled").getAsBoolean());
        assertEquals(0, body.get("total").getAsLong());
        assertEquals(0, body.getAsJsonArray("counts").size());
        // Counts recorded before it was turned off are still there to read.
        assertEquals(3, read("?context=" + claims).get("total").getAsLong());

    }

    @Test
    @DisplayName("Each read is recorded in the audit log, naming the reader and the filters")
    void readsAreAudited() throws Exception {

        read("?context=" + claims + "&from=2026-09-02");

        final Document recorded = mongoClient.getDatabase("philter").getCollection("audit_events")
                .find(new Document("event", "pii_counts_retrieved").append("api_key_id", MongoIds.objectId(adminUserId)))
                .first();
        assertNotNull(recorded, "reading the counts must be audited, naming the reader");
        assertTrue(recorded.getString("details").contains("context: " + claims), recorded.getString("details"));
        assertTrue(recorded.getString("details").contains("from: 2026-09-02"), recorded.getString("details"));
        assertTrue(recorded.getString("details").contains("total: 2"), recorded.getString("details"));

    }

    @Test
    @DisplayName("An unfiltered read records no context, so it differs from a filter on a context named any")
    void anUnfilteredReadRecordsNoContext() throws Exception {

        read("?from=2026-01-01");
        read("?context=any&from=2026-01-02");

        assertFalse(auditedDetails("from: 2026-01-01").contains("context:"), auditedDetails("from: 2026-01-01"));
        assertTrue(auditedDetails("from: 2026-01-02").contains(", context: any,"), auditedDetails("from: 2026-01-02"));

    }

    /** The details of the admin's pii_counts_retrieved event that contain {@code marker}. */
    private String auditedDetails(final String marker) {
        for (final Document recorded : mongoClient.getDatabase("philter").getCollection("audit_events")
                .find(new Document("event", "pii_counts_retrieved").append("api_key_id", MongoIds.objectId(adminUserId)))) {
            if (recorded.getString("details").contains(marker)) {
                return recorded.getString("details");
            }
        }
        throw new AssertionError("no pii_counts_retrieved event with " + marker);
    }

    @Test
    @DisplayName("Requires the pii-counts:read scope and an administrator")
    void refusals() throws Exception {

        final HttpResponse<String> noScope = get("", createKey(adminUserId, Set.of(ApiKeyScope.SETTINGS_READ.getScope())));
        assertEquals(403, noScope.statusCode());
        assertTrue(noScope.body().contains("pii-counts:read"), "the refusal should name the missing scope: " + noScope.body());

        final HttpResponse<String> notAdmin = get("", createKey(userId(createUser("counts-user-", "user")), ApiKeyScope.all()));
        assertEquals(403, notAdmin.statusCode());
        assertFalse(notAdmin.body().contains(claims), "a refusal returns no counts");

        assertEquals(401, get("", null).statusCode());

    }

    @Test
    @DisplayName("A date that is not YYYY-MM-DD, or a reversed range, is a 400")
    void badDates() throws Exception {

        assertEquals(400, get("?from=yesterday", adminKey).statusCode());
        assertEquals(400, get("?to=2026-09-01T00:00:00Z", adminKey).statusCode());
        assertEquals(400, get("?from=2026-09-03&to=2026-09-01", adminKey).statusCode());
        assertEquals(400, get("?to=%2B999999999-12-31", adminKey).statusCode(), "a year storage cannot hold");
        assertEquals(400, get("?from=-999999999-01-01", adminKey).statusCode());
        assertEquals(200, get("?from=0001-01-01&to=9999-12-31", adminKey).statusCode(), "the full range of years");

    }

}
