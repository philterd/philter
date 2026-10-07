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

import ai.philterd.philter.api.security.SecurityConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests refused before they reach a controller, by Tomcat or by Spring Security's firewall, are answered
 * with Philter's JSON error shape. Sent over a raw socket, since an HTTP client normalizes or refuses such paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class RefusedPathErrorsIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private int port;
    private String key;

    @BeforeEach
    void setUp() {
        port = environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "errors-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, "user", policyDataService, contextDataService, "test").isSuccessful());
        key = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test", ApiKeyScope.all()).getMessage();
    }

    private record RawResponse(int status, String contentType, String body) { }

    private RawResponse get(final String path, final String accept) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            final OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + key + "\r\nAccept: " + accept
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String head = response.substring(0, response.indexOf("\r\n\r\n"));
            String body = response.substring(response.indexOf("\r\n\r\n") + 4);
            if (head.toLowerCase().contains("transfer-encoding: chunked")) {
                // One chunk, then the terminating zero-length chunk.
                body = body.isEmpty() || body.startsWith("0\r\n") ? "" : body.substring(body.indexOf("\r\n") + 2, body.lastIndexOf("\r\n0\r\n"));
            }
            String contentType = "";
            for (final String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-type:")) {
                    contentType = line.substring("content-type:".length()).trim();
                }
            }
            return new RawResponse(Integer.parseInt(head.split(" ")[1]), contentType, body);
        }
    }

    private static String message(final RawResponse response) {
        assertTrue(response.contentType().startsWith("application/json"), response.contentType() + ": " + response.body());
        return new Gson().fromJson(response.body(), JsonObject.class).get("message").getAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/holds/a%2Fb", "/api/holds/a%5Cb", "/api/holds/a%00b", "/api/holds/a%zzb", "/api/holds/a b"})
    @DisplayName("A path Tomcat refuses is answered with JSON, not Tomcat's HTML page")
    void tomcatRefusalsAreJson(final String path) throws Exception {
        final RawResponse response = get(path, "text/html");
        assertEquals(400, response.status(), response.body());
        assertTrue(message(response).startsWith("The request could not be processed"), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/holds/a%3Bb", "/api/holds/a;b", "/api/holds/a%25b", "/api/holds/.", "/api/holds/..", "/api//holds"})
    @DisplayName("A path the firewall refuses is answered with JSON naming why, not Spring's default body")
    void firewallRefusalsAreJson(final String path) throws Exception {
        final RawResponse response = get(path, "text/html");
        assertEquals(400, response.status(), response.body());
        assertEquals(SecurityConfig.REQUEST_REJECTED, message(response));
    }

    @Test
    @DisplayName("Headers over Tomcat's limit are answered with JSON too")
    void oversizedHeadersAreJson() throws Exception {
        final RawResponse response = get("/api/policies?q=" + "x".repeat(10_000), "text/html");
        assertEquals(400, response.status(), response.body());
        assertTrue(message(response).startsWith("The request could not be processed"), response.body());
    }

    @Test
    @DisplayName("A response that deliberately has no body still has none")
    void bodilessResponsesStayBodiless() throws Exception {
        // A 404 for an owner that does not exist carries no body, so it cannot be told from one the caller may not reach.
        final RawResponse response = get("/api/policies?owner=nobody-" + UUID.randomUUID(), "application/json");
        assertEquals(404, response.status());
        assertEquals("", response.body());
    }

    @Test
    @DisplayName("Ordinary errors keep their own messages")
    void ordinaryErrorsUnchanged() throws Exception {
        assertEquals("Not found.", message(get("/api/nope", "application/json")));
    }

}
