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

import ai.philterd.philter.config.SignInConfig;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.services.cache.SignInThrottle;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
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
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in throttling end to end, with small limits: a username locked after consecutive failures,
 * refused even with the right password until the lock lapses, a count that a success resets, and a
 * per-address rate limit that reads the client address through the trusted-proxy rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SignInThrottleIT {

    private static final int MAX_FAILURES = 3;
    private static final int LOCKOUT_SECONDS = 2;
    private static final int RATE_PER_MINUTE = 6;

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
        @Bean
        public SignInThrottle signInThrottle() {
            return new SignInThrottle("", 0, "", false, MAX_FAILURES, LOCKOUT_SECONDS, RATE_PER_MINUTE);
        }
    }

    private static final String PASSWORD = "a-throttle-test-password-0123";

    /** A fresh documentation-range address per test, so each test has its own rate-limit window. */
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

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

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String clientIp;

    @BeforeEach
    void setUp() {
        SignInConfig.setOverrideForTesting(true);
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        clientIp = "203.0.113." + NEXT_ADDRESS.getAndIncrement();
    }

    @AfterEach
    void tearDown() {
        SignInConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser() {
        final String username = "throttle-" + UUID.randomUUID();
        userService.createUser("req", username, "user", policyDataService, contextDataService, "test");
        final UserEntity user = userService.findByUsername(username);
        userService.setPassword("req", user, PASSWORD, false, "test", null, null);
        return username;
    }

    private HttpResponse<String> post(final String path, final String body) throws Exception {
        // The test connects over loopback, a trusted proxy by default, so this header names the client.
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", clientIp)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signIn(final String username, final String password) throws Exception {
        return post("/api/sign-in", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private int audited(final String event, final String detail) throws Exception {
        final ObjectId admin = userService.findByUsername("admin").getId();
        final String key = apiKeyDataService.createApiKey("req", admin, "test", ApiKeyScope.all()).getMessage();
        final HttpResponse<String> audit = httpClient.send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/audit?limit=500&event=" + event))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        int count = 0;
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = element.getAsJsonObject();
            if (e.toString().contains(detail)) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("Consecutive failures lock the username, even against the right password, until the lock lapses")
    void locksAUsername() throws Exception {

        final String username = seedUser();
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, signIn(username, "not-the-password-0123").statusCode());
        }

        final HttpResponse<String> locked = signIn(username, PASSWORD);
        assertEquals(429, locked.statusCode(), "refused before the password is checked");
        assertEquals(String.valueOf(LOCKOUT_SECONDS), locked.headers().firstValue("Retry-After").orElse(null));
        assertEquals(429, signIn(username, PASSWORD).statusCode());
        assertEquals(1, audited("sign_in_locked", "username: " + username), "the lock is audited once");
        assertTrue(audited("sign_in_locked", clientIp) >= 1, "with the client address");

        Thread.sleep(LOCKOUT_SECONDS * 1000L + 500);
        assertEquals(200, signIn(username, PASSWORD).statusCode(), "the lock clears on its own");

    }

    @Test
    @DisplayName("A burst of parallel guesses gets no more password checks than the limit")
    void parallelGuessesCannotOutrunTheLimit() throws Exception {

        final String username = seedUser();
        final int burst = 12;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(burst)) {
            for (int i = 0; i < burst; i++) {
                // Each from its own address, so the per-address limit does not stop the burst first.
                final String address = "198.51.100." + (10 + i);
                results.add(executor.submit(() -> {
                    start.await();
                    return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/sign-in"))
                            .header("Content-Type", "application/json")
                            .header("X-Forwarded-For", address)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"" + username + "\",\"password\":\"not-the-password-0123\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
            }
            start.countDown();
            int checked = 0;
            for (final var result : results) {
                final int status = result.get();
                assertTrue(status == 401 || status == 429, "unexpected " + status);
                if (status == 401) {
                    checked++;
                }
            }
            assertTrue(checked <= MAX_FAILURES, checked + " passwords were checked; the limit is " + MAX_FAILURES);
        }
        assertEquals(429, signIn(username, PASSWORD).statusCode(), "and the username is locked");

    }

    @Test
    @DisplayName("A successful sign-in resets the count, so only consecutive failures lock")
    void aSuccessResetsTheCount() throws Exception {
        final String username = seedUser();
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            signIn(username, "not-the-password-0123");
        }
        assertEquals(200, signIn(username, PASSWORD).statusCode());
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            signIn(username, "not-the-password-0123");
        }
        assertEquals(200, signIn(username, PASSWORD).statusCode(), "not locked");
    }

    @Test
    @DisplayName("An unknown username locks too, so a lock says nothing about which usernames exist")
    void unknownUsernamesLockAlike() throws Exception {
        final String username = "no-such-user-" + UUID.randomUUID();
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, signIn(username, "anything-at-all-0123").statusCode());
        }
        assertEquals(429, signIn(username, "anything-at-all-0123").statusCode());
    }

    @Test
    @DisplayName("Sign-in requests are limited per client address, across both steps, and the first refusal is audited")
    void limitsRequestsPerAddress() throws Exception {

        final String username = seedUser();
        for (int i = 0; i < RATE_PER_MINUTE - 1; i++) {
            assertEquals(200, signIn(username, PASSWORD).statusCode());
        }
        assertEquals(401, post("/api/sign-in/mfa", "{\"challenge\":\"none\",\"code\":\"000000\"}").statusCode(),
                "the MFA step counts toward the same limit");

        final HttpResponse<String> refused = signIn(username, PASSWORD);
        assertEquals(429, refused.statusCode());
        assertEquals("60", refused.headers().firstValue("Retry-After").orElse(null));
        assertEquals(429, post("/api/sign-in/mfa", "{\"challenge\":\"none\",\"code\":\"000000\"}").statusCode());
        assertEquals(429, signIn(username, PASSWORD).statusCode());
        assertEquals(1, audited("sign_in_rate_limited", clientIp), "only the first refusal in the window is audited");

        clientIp = "203.0.113.250";
        assertEquals(200, signIn(username, PASSWORD).statusCode(), "another address has its own limit");

    }

}
