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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    @Autowired private RequestRejectedHandler requestRejectedHandler;

    private int port;
    private String key;

    @BeforeEach
    void setUp() {
        port = environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "errors-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, "user", policyDataService, contextDataService, "test").isSuccessful());
        key = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test", ApiKeyScope.all()).getMessage();
    }

    private record RawResponse(int status, String contentType, String body, Map<String, String> headers) { }

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
            final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (final String line : head.substring(head.indexOf("\r\n") + 2).split("\r\n")) {
                headers.put(line.substring(0, line.indexOf(':')).trim(), line.substring(line.indexOf(':') + 1).trim());
            }
            return new RawResponse(Integer.parseInt(head.split(" ")[1]), headers.getOrDefault("Content-Type", ""), body, headers);
        }
    }

    private static String message(final RawResponse response) {
        assertTrue(response.contentType().startsWith("application/json"), response.contentType() + ": " + response.body());
        return new Gson().fromJson(response.body(), JsonObject.class).get("message").getAsString();
    }

    private static String reason(final RawResponse response) {
        return new Gson().fromJson(response.body(), JsonObject.class).get("reason").getAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/holds/a%2Fb", "/api/holds/a%5Cb", "/api/holds/a%00b", "/api/holds/a%zzb", "/api/holds/a b"})
    @DisplayName("A path Tomcat refuses is answered with JSON, not Tomcat's HTML page")
    void tomcatRefusalsAreJson(final String path) throws Exception {
        final RawResponse response = get(path, "text/html");
        assertEquals(400, response.status(), response.body());
        assertTrue(message(response).startsWith("The request could not be processed"), response.body());
        assertEquals("invalid_request", reason(response), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/holds/a%3Bb", "/api/holds/a;b", "/api/holds/a%25b", "/api/holds/.", "/api/holds/..", "/api//holds"})
    @DisplayName("A path the firewall refuses is answered with JSON naming why, not Spring's default body")
    void firewallRefusalsAreJson(final String path) throws Exception {
        final RawResponse response = get(path, "text/html");
        assertEquals(400, response.status(), response.body());
        assertEquals(SecurityConfig.REQUEST_REJECTED, message(response));
        assertEquals("invalid_request", reason(response), response.body());
    }

    /** The headers the security chains write; HSTS only over HTTPS, so it is checked separately below. */
    private static final List<String> SECURITY_HEADERS = List.of("X-Content-Type-Options", "X-Frame-Options",
            "Content-Security-Policy", "Cache-Control", "Pragma", "Expires", "X-XSS-Protection");

    @ParameterizedTest
    @CsvSource({"/api/policies//details, /api/policies/x/details", "/actuator//health, /actuator/nope"})
    @DisplayName("A path the firewall refuses carries the same security headers as its chain's other responses")
    void firewallRefusalsCarryTheSecurityHeaders(final String refusedPath, final String ordinaryPath) throws Exception {
        final RawResponse refused = get(refusedPath, "application/json");
        assertEquals(400, refused.status(), refused.body());
        assertEquals(SecurityConfig.REQUEST_REJECTED, message(refused));
        assertEquals("nosniff", refused.headers().get("X-Content-Type-Options"), refused.headers().toString());

        final RawResponse ordinary = get(ordinaryPath, "application/json");
        assertEquals("nosniff", ordinary.headers().get("X-Content-Type-Options"), ordinary.headers().toString());
        for (final String header : SECURITY_HEADERS) {
            assertEquals(ordinary.headers().get(header), refused.headers().get(header), header + ": " + refused.headers());
        }
    }

    @Test
    @DisplayName("A path the firewall refuses over HTTPS carries Strict-Transport-Security")
    void firewallRefusalsOverHttpsCarryHsts() throws Exception {
        // The tests run over plain HTTP, and HSTS is only written to a secure request, so the handler is called directly.
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/policies//details");
        request.setSecure(true);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        requestRejectedHandler.handle(request, response, new RequestRejectedException("empty segment"));

        assertEquals(400, response.getStatus());
        assertEquals("max-age=31536000 ; includeSubDomains", response.getHeader("Strict-Transport-Security"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals(SecurityConfig.REQUEST_REJECTED,
                new Gson().fromJson(response.getContentAsString(), JsonObject.class).get("message").getAsString());
    }

    @Test
    @DisplayName("Headers over Tomcat's limit are answered with JSON too")
    void oversizedHeadersAreJson() throws Exception {
        final RawResponse response = get("/api/policies?q=" + "x".repeat(10_000), "text/html");
        assertEquals(400, response.status(), response.body());
        assertTrue(message(response).startsWith("The request could not be processed"), response.body());
        assertEquals("invalid_request", reason(response), response.body());
    }

    @Test
    @DisplayName("A 404 for an owner that does not exist says only not found, as an unknown path does")
    void notFoundSaysNothingMore() throws Exception {
        // The same body as any other 404, so it cannot be told from one the caller may not reach.
        final RawResponse response = get("/api/policies?owner=nobody-" + UUID.randomUUID(), "application/json");
        assertEquals(404, response.status());
        assertEquals(get("/api/nope", "application/json").body(), response.body());
        assertEquals("{\"message\":\"Not found.\",\"reason\":\"not_found\"}", response.body());
    }

    @Test
    @DisplayName("Ordinary errors keep their own messages")
    void ordinaryErrorsUnchanged() throws Exception {
        assertEquals("Not found.", message(get("/api/nope", "application/json")));
    }

}
