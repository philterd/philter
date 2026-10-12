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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.contract.StorageUnderTest;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PiiCountDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.SignInChallengeDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The MongoDB storage, built as {@link MongoStorageConfiguration} builds it for Philter, for the contract tests. */
final class MongoStorageUnderTest implements StorageUnderTest {

    private final MongoClient mongoClient;
    private final MongoStorageConfiguration configuration;
    private final EncryptionService encryption = new TestEncryptionService();
    private final ContextCache contextCache = new ContextCache("", 0, "", false);
    private final ApiKeyCache apiKeyCache = new ApiKeyCache("", 0, "", false);
    private final Map<String, Object> services = new HashMap<>();

    MongoStorageUnderTest(final MongoClient mongoClient) {
        this.mongoClient = mongoClient;
        this.configuration = new MongoStorageConfiguration(mongoClient);
    }

    /** Each service is created once and shared, also by concurrent callers. */
    @SuppressWarnings("unchecked")
    private synchronized <T> T once(final String name, final Supplier<T> create) {
        Object service = services.get(name);
        if (service == null) {
            service = create.get();
            services.put(name, service);
        }
        return (T) service;
    }

    @Override
    public AdminSettingsDataService adminSettings() {
        return once("adminSettings", () -> configuration.adminSettingsDataService(encryption));
    }

    @Override
    public ApiKeyDataService apiKeys() {
        return once("apiKeys", () -> configuration.apiKeyDataService(apiKeyCache));
    }

    @Override
    public ContextDataService contexts() {
        return once("contexts", () -> configuration.contextDataService(contextCache));
    }

    @Override
    public ContextEntryDataService contextEntries() {
        return once("contextEntries", () -> configuration.contextEntryDataService(contextCache));
    }

    @Override
    public CustomListDataService customLists() {
        return once("customLists", () -> configuration.customListService(encryption));
    }

    @Override
    public LedgerDataService ledger() {
        return once("ledger", () -> configuration.ledgerService(
                new SigningService(signingKeys(), adminSettings()), encryption));
    }

    @Override
    public LegalHoldDataService legalHolds() {
        return once("legalHolds", configuration::legalHoldDataService);
    }

    @Override
    public PendingDocumentDataService pendingDocuments() {
        return once("pendingDocuments", () -> configuration.pendingDocumentDataService(encryption));
    }

    @Override
    public PolicyDataService policies() {
        return once("policies", () -> configuration.policyDataService(new Gson(), new RedactionCache()));
    }

    @Override
    public PolicyVersionDataService policyVersions() {
        return once("policyVersions", configuration::policyVersionDataService);
    }

    @Override
    public RedactListsDataService redactLists() {
        return once("redactLists", () -> configuration.redactListsService(encryption));
    }

    @Override
    public SignInChallengeDataService signInChallenges() {
        return once("signInChallenges", configuration::signInChallengeDataService);
    }

    @Override
    public SigningKeyDataService signingKeys() {
        return once("signingKeys", () -> configuration.signingKeyDataService(encryption));
    }

    @Override
    public UserService users() {
        return once("users", () -> configuration.userService(encryption));
    }

    @Override
    public WebhookDeliveryDataService webhookDeliveries() {
        return once("webhookDeliveries", () -> configuration.webhookDeliveryDataService(encryption));
    }

    @Override
    public AuditEventPublisher auditEvents() {
        return once("auditEvents", configuration::auditEventPublisher);
    }

    @Override
    public AuditLogService auditLog() {
        return once("auditLog", configuration::auditLogService);
    }

    @Override
    public PiiCountAggregatePublisher piiCounts() {
        return once("piiCounts", () -> configuration.piiCountAggregatePublisher(adminSettings()));
    }

    @Override
    public PiiCountDataService piiCountData() {
        return once("piiCountData", configuration::piiCountDataService);
    }

    @Override
    public EvidenceGuard evidenceGuard() {
        return once("evidenceGuard", configuration::evidenceGuard);
    }

    @Override
    public boolean storageContains(final String text) {
        final com.mongodb.client.MongoDatabase philter = mongoClient.getDatabase("philter");
        for (final String name : philter.listCollectionNames()) {
            for (final Document document : philter.getCollection(name).find()) {
                if (contains(document, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(final Object value, final String text) {
        if (value instanceof Document document) {
            return document.values().stream().anyMatch(field -> contains(field, text));
        }
        if (value instanceof java.util.Collection<?> values) {
            return values.stream().anyMatch(element -> contains(element, text));
        }
        if (value instanceof org.bson.types.Binary binary) {
            return new String(binary.getData(), java.nio.charset.StandardCharsets.UTF_8).contains(text);
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains(text);
        }
        return value != null && value.toString().contains(text);
    }

    @Override
    public void damageLedgerChain(final String userId, final String documentId) {
        mongoClient.getDatabase("philter").getCollection("ledger").updateMany(
                Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("document_id", documentId)),
                Updates.set("replacement_encrypted_key", "not-a-key"));
    }

    @Override
    public StorageUnderTest anotherInstance() {
        return new MongoStorageUnderTest(mongoClient);
    }

    @Override
    public void expireSessionKey(final String keyId) {
        final Date past = Date.from(Instant.now().minus(1, ChronoUnit.MINUTES));
        mongoClient.getDatabase("philter").getCollection("api_keys").updateOne(Filters.eq("_id", MongoIds.objectId(keyId)),
                Updates.combine(Updates.set("expires_at", past), Updates.set("idle_expires_at", past)));
    }

    @Override
    public void expireSignInChallenge(final String token) {
        mongoClient.getDatabase("philter").getCollection("sign_in_challenges").updateOne(
                Filters.eq("token_hash", EncryptionService.hashSha256(token)),
                Updates.set("expires_at", Date.from(Instant.now().minus(1, ChronoUnit.MINUTES))));
    }

    @Override
    public void storePiiCounts(final String context, final LocalDate day, final Map<String, Long> counts,
                               final long totalDocuments) {
        final Date bucketStart = Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        mongoClient.getDatabase("philter").getCollection(MongoPiiCountAggregatePublisher.COLLECTION).insertOne(
                new Document("context", context).append("bucket_start", bucketStart)
                        .append("counts", new Document(new HashMap<>(counts))).append("total_documents", totalDocuments)
                        .append("updated_at", bucketStart));
    }

}
