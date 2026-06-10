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
package ai.philterd.philter;

import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full application against an in-memory MongoDB, fetches the OpenAPI specification that
 * springdoc generates from the Spring annotations, and writes it to {@code target/openapi.json}.
 * The CI docs workflow copies that file into the published documentation, so this test is the
 * build step that turns the annotation-derived specification into a distributable artifact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class OpenApiExportIT {

    @Autowired
    private Environment environment;

    /**
     * Replaces the application's {@code mongoClient} bean (which connects to the
     * {@code MONGODB_CONNECTION_STRING} environment variable) with a client backed by an
     * in-process, in-memory mongo-java-server, so the application can boot with no external
     * dependencies.
     */
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
            // The production bean requires the PHILTER_ENCRYPTION_KEY environment variable.
            return new TestEncryptionService();
        }

    }

    @Test
    void exportOpenApiSpecification() throws Exception {

        final int port = environment.getRequiredProperty("local.server.port", Integer.class);
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build();

        try (final HttpClient httpClient = HttpClient.newHttpClient()) {

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "The OpenAPI specification should be publicly accessible.");

            final JsonObject specification = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(specification.has("openapi"), "The response should be an OpenAPI specification.");
            assertFalse(specification.getAsJsonObject("paths").keySet().isEmpty(),
                    "The specification should document at least one endpoint.");

            // Pretty-print so the published artifact diffs cleanly between releases.
            final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            final Path output = Path.of("target", "openapi.json");
            Files.writeString(output, gson.toJson(specification));

        }

    }

}
