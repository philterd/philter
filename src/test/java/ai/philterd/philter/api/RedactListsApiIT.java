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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One redact list read and replaced on its own, end to end: the revision travels in ETag and If-Match
 * through Philter's filters, a stale revision is refused, and the other list is left alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class RedactListsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

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
        final String name = "redact-lists-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", name, null, "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        key = apiKeyDataService.createApiKey("req", userService.findByUsername(name).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String ifMatch, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A list is replaced at the revision its ETag gave, and a stale revision is refused")
    void replacesOneListAtTheRevisionRead() throws Exception {

        final HttpResponse<String> read = send("GET", "/api/redact-lists/always", null, null);
        assertEquals(200, read.statusCode(), read.body());
        final String eTag = read.headers().firstValue("ETag").orElseThrow();
        assertEquals("\"0\"", eTag, "a list never written is at revision 0");

        final HttpResponse<String> first = send("PUT", "/api/redact-lists/always", eTag, "{\"terms\":[\"ssn\"]}");
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("\"1\"", first.headers().firstValue("ETag").orElseThrow());

        // A second client that read the same revision is refused, and the first client's terms stand.
        final HttpResponse<String> stale = send("PUT", "/api/redact-lists/always", eTag, "{\"terms\":[\"other\"]}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("redact_list_changed", gson.fromJson(stale.body(), JsonObject.class).get("reason").getAsString());

        final JsonObject both = gson.fromJson(send("GET", "/api/redact-lists", null, null).body(), JsonObject.class);
        assertEquals("ssn", both.getAsJsonArray("alwaysRedact").get(0).getAsString());
        assertEquals(1, both.get("alwaysRedactRevision").getAsInt());
        assertEquals(0, both.getAsJsonArray("neverRedact").size(), "the other list is left alone");
        assertEquals(0, both.get("neverRedactRevision").getAsInt());

    }

}
