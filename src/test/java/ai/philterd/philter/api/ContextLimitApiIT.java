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

/** The documented limit of ten contexts per user, counting {@code default}, and its response. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ContextLimitApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired
    private Environment environment;

    @Autowired
    private UserService userService;

    @Autowired
    private ApiKeyDataService apiKeyDataService;

    @Autowired
    private PolicyDataService policyDataService;

    @Autowired
    private ContextDataService contextDataService;

    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private String newUserKey() {
        final String username = "limit-" + UUID.randomUUID();
        userService.createUser("req", username, "user", policyDataService, contextDataService, "test");
        return apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private static String reason(final HttpResponse<String> response) {
        final com.google.gson.JsonObject body = new com.google.gson.Gson().fromJson(response.body(), com.google.gson.JsonObject.class);
        return body.has("reason") ? body.get("reason").getAsString() : null;
    }

    private HttpResponse<String> send(final String method, final String path, final String key) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A user can have ten contexts, counting default, and the eleventh is refused with 409 and a reason")
    void limitsEachUserToTenContexts() throws Exception {

        final String key = newUserKey();
        for (int i = 1; i <= ContextDataService.MAXIMUM_CONTEXTS_PER_USER - 1; i++) {
            assertEquals(200, send("POST", "/api/contexts?name=c" + i, key).statusCode(), "context " + i + " of 9 after default");
        }

        final HttpResponse<String> eleventh = send("POST", "/api/contexts?name=one-too-many", key);
        assertEquals(409, eleventh.statusCode());
        assertEquals("context_limit_reached", reason(eleventh), eleventh.body());
        assertTrue(eleventh.body().contains("Maximum number of contexts reached."), eleventh.body());

        final HttpResponse<String> duplicate = send("POST", "/api/contexts?name=c1", key);
        assertEquals(409, duplicate.statusCode());
        assertEquals("context_exists", reason(duplicate), "at the limit, a name in use is still reported as a duplicate");

        final HttpResponse<String> blank = send("POST", "/api/contexts?name=%20", key);
        assertEquals(400, blank.statusCode(), "a blank name is still a bad request: " + blank.body());

        assertEquals(200, send("POST", "/api/contexts?name=c1", newUserKey()).statusCode(),
                "another user's contexts do not count toward the limit");

        assertEquals(200, send("DELETE", "/api/contexts/c1", key).statusCode());
        assertEquals(200, send("POST", "/api/contexts?name=one-too-many", key).statusCode(), "deleting one makes room");

    }

}
