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
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code API_IP_ALLOWLIST} is read from the environment and enforced.
 *
 * <p>{@link ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter} parses the variable once,
 * into a static field, when the class is first loaded. Nothing inside a running JVM can change that,
 * so {@code ApiFilterChainIT} exercises the allowlist through a test override instead: it proves the
 * filter enforces an allowlist, but it routes around the environment variable itself. A typo in the
 * variable name would pass every test in that class.
 *
 * <p>This test closes that gap. It runs in its own forked JVM, started by the
 * {@code failsafe-environment-integration-tests} execution in the POM with
 * {@code API_IP_ALLOWLIST=10.0.0.0/8} set in the environment, and sets no override. The assertions
 * below therefore depend on the variable being read, parsed, and applied to a real request.
 *
 * <p>Named {@code *EnvironmentIT} so the POM can route it to that fork. Prefer {@code ApiFilterChainIT}
 * for anything that does not genuinely require a process-level environment variable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ApiIpAllowlistEnvironmentIT {

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
    private String apiKey;

    @TestConfiguration
    static class InMemoryMongoConfiguration {

        @Bean(destroyMethod = "shutdown")
        MongoServer mongoServer() {
            return new MongoServer(new MemoryBackend());
        }

        @Bean
        MongoClient mongoClient(final MongoServer mongoServer) {
            final InetSocketAddress address = mongoServer.bind();
            return MongoClients.create("mongodb://" + address.getHostName() + ":" + address.getPort());
        }

        @Bean
        EncryptionService encryptionService() {
            return new TestEncryptionService();
        }

    }

    @BeforeEach
    void setUp() {

        // If this fails, the fork was started without the environment variable, which would make the
        // assertions below pass for the wrong reason (or fail confusingly). Fail with the reason instead.
        assertEquals("10.0.0.0/8", System.getenv("API_IP_ALLOWLIST"),
                "this test must run in the fork configured by failsafe-environment-integration-tests");

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        final String username = "allowlist-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");

        final ObjectId userId = userService.findByUsername(username).getId();
        final ServiceResponse keyResponse = apiKeyDataService.createApiKey("req", userId, "test");
        assertTrue(keyResponse.isSuccessful(), "the API key must be created");
        apiKey = keyResponse.getMessage();

    }

    @Test
    @DisplayName("An address outside the allowlist read from API_IP_ALLOWLIST is rejected with 403")
    void addressOutsideTheConfiguredAllowlistIsForbidden() throws Exception {

        // An authenticated endpoint: the allowlist is checked after the key resolves, so the
        // unauthenticated endpoints (/api/status, /api/health, /api/signing-key) never reach it.
        // The test client connects from loopback, which 10.0.0.0/8 excludes.
        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build());

        assertEquals(403, response.statusCode());

    }

    @Test
    @DisplayName("An address inside the allowlist read from API_IP_ALLOWLIST is allowed")
    void addressInsideTheConfiguredAllowlistIsAllowed() throws Exception {

        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies"))
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Forwarded-For", "10.1.2.3")
                .GET()
                .build());

        assertEquals(200, response.statusCode());

    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

}
