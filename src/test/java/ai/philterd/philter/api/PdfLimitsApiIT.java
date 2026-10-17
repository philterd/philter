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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A PDF whose page would render to more pixels than Phileas allows is refused with 413 rather than rendered. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PdfLimitsApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    @Test
    @DisplayName("A PDF with a page too large to render returns 413")
    void aHugePageIsRefused() throws Exception {
        final String username = "pdf-limits-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, "user", policyDataService, contextDataService, "test").isSuccessful());
        final String key = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();

        final byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 200 x 200 inches: about 900 million pixels at the default 150 DPI.
            document.addPage(new PDPage(new PDRectangle(200 * 72, 200 * 72)));
            document.save(out);
            pdf = out.toByteArray();
        }

        final String baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?async=false"))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/pdf")
                    .header("Accept", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode(), response.body());
            final JsonObject body = new Gson().fromJson(response.body(), JsonObject.class);
            assertEquals("payload_too_large", body.get("reason").getAsString());
            assertTrue(body.get("message").getAsString().contains("pixels"), response.body());
        }
    }

}
