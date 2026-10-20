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
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives, over HTTP, the flows that store data outside the plain listings: an asynchronous document through the
 * queue to its result, re-identifying a replacement, and reading the PII counts. These reach storage only
 * through Philter's API, so they run against every storage implementation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true", "philter.worker.poll-interval-ms=200",
                "philter.worker.notification-interval-ms=200"})
class StorageFlowsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String FPE_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"FPE_ENCRYPT_REPLACE\"}]}}}";

    private static final String CRYPTO_POLICY = "{\"crypto\":{\"key\":\"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\"},"
            + "\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"CRYPTO_REPLACE\"}]},"
            + "\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"CRYPTO_REPLACE\"}]}}}";

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

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
    private String apiKey;
    private String userId;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        final String username = "flows-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "admin", policyDataService,
                contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        userId = userService.findByUsername(username).getId();
        apiKey = apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private HttpRequest.Builder authenticated(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void savePolicy(final String name, final String json) throws Exception {
        final HttpResponse<String> saved = send(authenticated("/api/policies?name=" + name)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
    }

    private static byte[] blankPdf() throws Exception {
        try (final PDDocument document = new PDDocument(); final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("An asynchronous document goes through the queue, is downloaded, listed, and deleted")
    void anAsynchronousDocumentCompletes() throws Exception {

        savePolicy("ssn-only", SSN_POLICY);

        final HttpResponse<String> accepted = send(authenticated("/api/filter?p=ssn-only&c=default&filename=claim.pdf"
                + "&fairness_key=reviewer-42")
                .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build());
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

        String status = null;
        JsonObject polledStatus = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            assertEquals(200, polled.statusCode(), polled.body());
            polledStatus = gson.fromJson(polled.body(), JsonObject.class);
            status = polledStatus.get("status").getAsString();
        }
        assertEquals("COMPLETE", status);

        // The status names the policy the document was submitted with, as the 202 did, so that revision can be read.
        assertEquals("ssn-only", polledStatus.get("policyName").getAsString());
        assertEquals(accepted.headers().firstValue("X-Philter-Policy-Version").orElseThrow(),
                polledStatus.get("policyVersion").getAsString());
        assertEquals(accepted.headers().firstValue("X-Philter-Policy-Hash").orElseThrow(),
                polledStatus.get("policyContentHash").getAsString());
        final HttpResponse<String> revision = send(authenticated("/api/policies/ssn-only/versions/"
                + polledStatus.get("policyVersion").getAsInt()).GET().build());
        assertEquals(200, revision.statusCode(), revision.body());

        final HttpResponse<byte[]> result = httpClient.send(authenticated("/api/documents/" + documentId).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().length > 0, "the redacted document is returned");

        final HttpResponse<String> listed = send(authenticated("/api/documents").GET().build());
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains(documentId), listed.body());
        assertTrue(listed.body().contains("\"policyName\":\"ssn-only\""), listed.body());

        // Found by part of the file name it was submitted with, ignoring case, and not by a name it does not have.
        final HttpResponse<String> found = send(authenticated("/api/documents?q=CLAIM").GET().build());
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains(documentId), found.body());
        final HttpResponse<String> missed = send(authenticated("/api/documents?q=invoice").GET().build());
        assertEquals(0, gson.fromJson(missed.body(), JsonObject.class).get("total").getAsInt(), missed.body());

        assertEquals(1, storedJobRecords(documentId), "the check reads the job's record before it is deleted");

        // Deleting waits until the completion notification has been dispatched.
        int deleted = 0;
        for (int attempt = 0; attempt < 100 && deleted != 200 && deleted != 204; attempt++) {
            deleted = send(authenticated("/api/documents/" + documentId).DELETE().build()).statusCode();
            if (deleted == 409) {
                Thread.sleep(200);
            }
        }
        assertTrue(deleted == 200 || deleted == 204, "deleted: " + deleted);
        assertEquals(404, send(authenticated("/api/documents/" + documentId + "/status").GET().build()).statusCode());
        assertEquals(0, storedJobRecords(documentId), "no record of the document, its input, or its output remains");

    }

    @Test
    @DisplayName("A request naming a context that does not exist is refused with 400, synchronous or not")
    void aMissingContextIsRefused() throws Exception {

        savePolicy("ssn-only", SSN_POLICY);
        final String query = "?p=ssn-only&c=no-such-context";

        final List<HttpResponse<String>> responses = List.of(
                send(authenticated("/api/filter" + query).header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("SSN 123-45-6789")).build()),
                send(authenticated("/api/explain" + query).header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("SSN 123-45-6789")).build()),
                send(authenticated("/api/filter" + query + "&async=false").header("Content-Type", "application/pdf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build()),
                send(authenticated("/api/filter" + query).header("Content-Type", "application/pdf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build()),
                send(authenticated("/api/filter" + query + "&async=false").header("Content-Type", "application/pdf")
                        .header("Accept", "application/zip").POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build()),
                send(authenticated("/api/filter" + query).header("Content-Type", "application/pdf")
                        .header("Accept", "application/zip").POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build()));
        for (final HttpResponse<String> response : responses) {
            assertEquals(400, response.statusCode(), response.request().uri() + " " + response.body());
            assertTrue(response.body().contains("Context does not exist."), response.body());
        }

    }

    @Test
    @DisplayName("A fairness key that is not valid, or is on a synchronous request, is refused")
    void anUnusableFairnessKeyIsRefused() throws Exception {

        savePolicy("ssn-only", SSN_POLICY);

        for (final String query : List.of("fairness_key=jane%40example.com", "fairness_key=", "fairness_key=" + "x".repeat(65),
                "fairness_key=reviewer-42&async=false")) {
            final HttpResponse<String> refused = send(authenticated("/api/filter?p=ssn-only&c=default&" + query)
                    .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(blankPdf())).build());
            assertEquals(400, refused.statusCode(), query + ": " + refused.body());
            assertTrue(refused.body().contains("fairness_key"), refused.body());
        }

    }

    @Test
    @DisplayName("A format-preserving replacement is reversed to its original")
    void aReplacementIsReidentified() throws Exception {

        savePolicy("fpe", FPE_POLICY);

        final HttpResponse<String> redacted = send(authenticated("/api/filter?p=fpe&c=default")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("123-45-6789")).build());
        assertEquals(200, redacted.statusCode(), redacted.body());
        final String replacement = redacted.body().trim();
        assertFalse(replacement.contains("123-45-6789"), replacement);

        final JsonObject request = new JsonObject();
        final com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        values.add(replacement);
        request.add("values", values);
        request.addProperty("strategy", "FPE_ENCRYPT_REPLACE");
        request.addProperty("reason", "storage test");
        final HttpResponse<String> reversed = send(authenticated("/api/reidentify")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build());
        assertEquals(200, reversed.statusCode(), reversed.body());
        assertTrue(reversed.body().contains("123-45-6789"), reversed.body());

    }

    @Test
    @DisplayName("A redacted text is restored to the original by re-identifying its CRYPTO_REPLACE values")
    void aRedactedTextIsReidentified() throws Exception {

        savePolicy("crypto", CRYPTO_POLICY);
        final String original = "Write to jane.doe@example.com about SSN 123-45-6789 today.";

        final HttpResponse<String> redacted = send(authenticated("/api/filter?p=crypto&c=default")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(original)).build());
        assertEquals(200, redacted.statusCode(), redacted.body());
        assertFalse(redacted.body().contains("123-45-6789") || redacted.body().contains("jane.doe"), redacted.body());

        final JsonObject request = new JsonObject();
        request.addProperty("text", redacted.body());
        request.addProperty("policyName", "crypto");
        request.addProperty("reason", "storage test");
        final HttpResponse<String> restored = send(authenticated("/api/reidentify/text")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build());
        assertEquals(200, restored.statusCode(), restored.body());
        final JsonObject body = gson.fromJson(restored.body(), JsonObject.class);
        assertEquals(original, body.get("text").getAsString());
        assertEquals(2, body.get("found").getAsInt());
        assertEquals(2, body.get("restored").getAsInt());
        assertEquals(0, body.get("failed").getAsInt());

        // Redacting needs only the redact scope; restoring needs reidentify.
        final String redactOnly = apiKeyDataService.createApiKey("req", userId, "test",
                java.util.Set.of(ApiKeyScope.REDACT.getScope())).getMessage();
        final HttpResponse<String> refused = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/reidentify/text"))
                .header("Authorization", "Bearer " + redactOnly).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build());
        assertEquals(403, refused.statusCode(), refused.body());

    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /** How many job records the storage this run uses holds for the document, read directly rather than through Philter. */
    private long storedJobRecords(final String documentId) {
        if (context.getBeanNamesForType(ai.philterd.philter.data.sqlite.SqliteDatabase.class).length > 0) {
            return context.getBean(ai.philterd.philter.data.sqlite.SqliteDatabase.class).read(connection -> {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM pending_documents WHERE document_id = ?")) {
                    statement.setString(1, documentId);
                    try (java.sql.ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        return rows.getLong(1);
                    }
                }
            });
        }
        return context.getBean(com.mongodb.client.MongoClient.class).getDatabase("philter")
                .getCollection("pending_documents").countDocuments(new org.bson.Document("document_id", documentId));
    }

    @Test
    @DisplayName("Only the selected storage is set up, and it is the storage this run is for")
    void onlyTheSelectedStorageIsSetUp() {
        final int mongoClients = context.getBeanNamesForType(com.mongodb.client.MongoClient.class).length;
        final int sqliteDatabases = context.getBeanNamesForType(ai.philterd.philter.data.sqlite.SqliteDatabase.class).length;
        final String used = sqliteDatabases > 0 ? "sqlite" : "mongodb";
        // The build sets the storage each run is for, so a run meant for SQLite cannot quietly use MongoDB.
        final String expected = System.getenv("EXPECTED_STORAGE_TYPE");
        if (expected != null) {
            assertEquals(expected, used, "the storage this run is for");
        }
        if ("sqlite".equals(used)) {
            assertEquals(0, mongoClients, "no MongoDB client is created with SQLite storage");
            assertEquals(1, sqliteDatabases);
        } else {
            assertTrue(mongoClients > 0, "MongoDB storage has a client");
        }
    }

    @Test
    @DisplayName("The PII counts are read through the API")
    void piiCountsAreRead() throws Exception {

        final HttpResponse<String> counts = send(authenticated("/api/pii-counts?context=default").GET().build());
        assertEquals(200, counts.statusCode(), counts.body());
        final JsonObject body = gson.fromJson(counts.body(), JsonObject.class);
        assertNotNull(body.get("recordingEnabled"));
        assertNotNull(body.get("total"));

    }

}
