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

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import ai.philterd.philter.utils.PathSafeNames;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
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
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names and usernames that cannot be used in a request path are refused, and names made before that can still
 * be removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PathSafeNamesApiIT {

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
    @Autowired private LegalHoldDataService legalHoldDataService;
    @Autowired private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId userId;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "path-" + UUID.randomUUID();
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
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hold(final String reference) {
        return new Gson().toJson(java.util.Map.of("reference", reference, "scopeType", "user"));
    }

    private void assertRefusedNamingTheRule(final HttpResponse<String> response) {
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(gson.fromJson(response.body(), JsonObject.class).get("message").getAsString().contains(PathSafeNames.RULE),
                response.body());
    }

    @Test
    @DisplayName("A hold reference, list name, or context name that cannot be used in a path is refused")
    void refusesNamesThatCannotBeUsedInAPath() throws Exception {

        assertRefusedNamingTheRule(send("POST", "/api/holds", hold("LIT/2026/001")));
        assertRefusedNamingTheRule(send("POST", "/api/holds", hold("..")));
        assertNull(legalHoldDataService.findByReference("LIT/2026/001", userId), "no hold was created");

        assertRefusedNamingTheRule(send("POST", "/api/contexts?name=" + encode("a/b"), null));
        assertRefusedNamingTheRule(send("POST", "/api/contexts?name=" + encode("a;b"), null));
        assertNull(contextDataService.findOne("a/b", userId), "no context was created");

        // A list name arrives in the path, so a / never reaches Philter, but a control character does.
        assertRefusedNamingTheRule(send("POST", "/api/lists/" + encode("a\tb"), "[\"one\"]"));
        assertNull(customListDataService.findOneByName("a\tb", userId), "no list was created");

    }

    @Test
    @DisplayName("Names the rule allows can be created and then read back through the path")
    void allowedNamesRoundTrip() throws Exception {
        for (final String reference : List.of("Smith v. Jones 2026", "...", ".hidden", "a?b#c", "Projet é")) {
            assertEquals(201, send("POST", "/api/holds", hold(reference)).statusCode(), reference);
            final HttpResponse<String> read = send("GET", "/api/holds/" + encode(reference), null);
            assertEquals(200, read.statusCode(), reference + ": " + read.body());
            assertEquals(reference, gson.fromJson(read.body(), JsonObject.class).get("reference").getAsString());
        }
    }

    /** An administrator's key, since only an administrator creates users. */
    private String adminKey() {
        final String admin = "path-admin-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", admin, null, "admin", policyDataService, contextDataService, "test")
                .isSuccessful());
        return apiKeyDataService.createApiKey("req", userService.findByUsername(admin).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private static String user(final String username) {
        return new Gson().toJson(java.util.Map.of("username", username));
    }

    @Test
    @DisplayName("A username that cannot be used in a path is refused")
    void refusesUsernamesThatCannotBeUsedInAPath() throws Exception {
        key = adminKey();
        for (final String username : List.of("ops/ci", "ops\\ci", "ops;ci", "ops%2Fci", "ops\tci", "..", " . ")) {
            assertRefusedNamingTheRule(send("POST", "/api/users", user(username)));
            assertNull(userService.findAnyByUsername(username.trim()), "no user was created for " + username);
        }
    }

    @Test
    @DisplayName("Usernames the rule allows, including an email address, are created and read back through the path")
    void allowedUsernamesRoundTrip() throws Exception {
        key = adminKey();
        final String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (final String username : List.of("first.last+ci-" + suffix + "@example.com", "ops ci " + suffix,
                "..." + suffix, "Équipe-" + suffix)) {
            assertEquals(201, send("POST", "/api/users", user(username)).statusCode(), username);
            final HttpResponse<String> read = send("GET", "/api/users/" + encode(username), null);
            assertEquals(200, read.statusCode(), username + ": " + read.body());
            assertEquals(username, gson.fromJson(read.body(), JsonObject.class).get("username").getAsString());
        }
    }

    @Test
    @DisplayName("A hold made before the rule, with a / in its reference, is released through the query")
    void releasesAnUnaddressableHold() throws Exception {

        // As an earlier build stored it, without the rule.
        final LegalHoldEntity hold = new LegalHoldEntity();
        hold.setUserId(userId);
        hold.setReference("LIT/2026/001");
        hold.setScopeType(LegalHoldEntity.SCOPE_USER);
        hold.setScopeValue("all");
        hold.setSetAt(new Date());
        hold.setSetByUserId(userId);
        mongoClient.getDatabase("philter").getCollection("legal_holds").insertOne(hold.toDocument());
        assertNotNull(legalHoldDataService.findByReference("LIT/2026/001", userId));

        assertEquals(400, send("DELETE", "/api/holds/" + encode("LIT/2026/001"), null).statusCode(),
                "its path cannot reach Philter");

        final HttpResponse<String> released = send("DELETE", "/api/holds?reference=" + encode("LIT/2026/001"), null);
        assertEquals(200, released.statusCode(), released.body());
        assertNull(legalHoldDataService.findByReference("LIT/2026/001", userId));

        final HttpResponse<String> missing = send("DELETE", "/api/holds?reference=" + encode("LIT/2026/001"), null);
        assertEquals(404, missing.statusCode(), missing.body());

        // Without the parameter, the request is refused as any missing parameter is, not with a 500.
        for (final String path : List.of("/api/holds", "/api/lists", "/api/contexts")) {
            final HttpResponse<String> noName = send("DELETE", path, null);
            assertEquals(400, noName.statusCode(), path + ": " + noName.body());
            assertTrue(gson.fromJson(noName.body(), JsonObject.class).has("message"), noName.body());
        }

    }

    @Test
    @DisplayName("A list and a context made before the rule are deleted through the query")
    void deletesAnUnaddressableListAndContext() throws Exception {

        final CustomListEntity list = new CustomListEntity();
        list.setUserId(userId);
        list.setName("a/b");
        list.setDescription("");
        list.setItems(List.of("one"));
        customListDataService.save(list);

        final ContextEntity context = new ContextEntity();
        context.setUserId(userId);
        context.setContextName("a/b");
        context.setSlot(5);
        contextDataService.save(context);

        assertEquals(204, send("DELETE", "/api/lists?name=" + encode("a/b"), null).statusCode());
        assertNull(customListDataService.findOneByName("a/b", userId));

        final HttpResponse<String> deleted = send("DELETE", "/api/contexts?name=" + encode("a/b"), null);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertNull(contextDataService.findOne("a/b", userId));

        assertFalse(send("GET", "/api/lists", null).body().contains("a/b"));

    }

}
