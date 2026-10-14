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
import ai.philterd.philter.config.StorageConfiguration;
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
import ai.philterd.philter.services.context.ContextServices;
import ai.philterd.philter.services.context.StoredContextServices;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.services.vectors.VectorServices;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * The MongoDB storage implementation: every data service, the audit log, and the services a redaction stores
 * through, backed by MongoDB. Used when {@code STORAGE_TYPE} is {@code mongodb}. Its services are
 * created only once the data is at the expected schema version.
 */
@Configuration
@ConditionalOnProperty(name = StorageConfiguration.PROPERTY, havingValue = StorageConfiguration.MONGODB)
@DependsOn(MongoConnectionConfiguration.SCHEMA_VERSION)
public class MongoStorageConfiguration {

    private final MongoClient mongoClient;

    public MongoStorageConfiguration(final MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    @Bean
    public PiiCountAggregatePublisher piiCountAggregatePublisher(final AdminSettingsDataService adminSettingsDataService) {
        return new MongoPiiCountAggregatePublisher(mongoClient, adminSettingsDataService);
    }

    @Bean
    public PiiCountDataService piiCountDataService() {
        return new MongoPiiCountDataService(mongoClient);
    }

    @Bean
    public ContextServices contextServices(final MongoContextEntryDataService contextEntryDataService) {
        return new StoredContextServices(contextEntryDataService);
    }

    @Bean
    public VectorServices vectorServices() {
        return new MongoVectorServices(mongoClient);
    }

    @Bean
    public AuditEventPublisher auditEventPublisher() {
        return new MongoDBAuditEventPublisher(mongoClient);
    }

    @Bean
    public AuditLogService auditLogService() {
        return new MongoAuditLogService(mongoClient);
    }

    @Bean
    public ContextDataService contextDataService(final ContextCache contextCache) {
        return new MongoContextDataService(mongoClient, auditEventPublisher(), contextEntryDataService(contextCache), vectorServices());
    }

    @Bean
    public EvidenceGuard evidenceGuard() {
        return new MongoEvidenceGuard(mongoClient);
    }

    @Bean
    public LegalHoldDataService legalHoldDataService() {
        return new MongoLegalHoldDataService(mongoClient, auditEventPublisher(), evidenceGuard());
    }

    @Bean
    public LedgerDataService ledgerService(final SigningService signingService,
            final EncryptionService encryptionService) {
        return new MongoLedgerDataService(mongoClient, encryptionService, auditEventPublisher(),
                legalHoldDataService(), signingService, evidenceGuard());
    }

    @Bean
    public MongoContextEntryDataService contextEntryDataService(final ContextCache contextCache) {
        return new MongoContextEntryDataService(mongoClient, contextCache);
    }

    @Bean
    public CustomListDataService customListService(final EncryptionService encryptionService) {
        return new MongoCustomListDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public RedactListsDataService redactListsService(final EncryptionService encryptionService,
                                                     final RedactionCache redactionCache) {
        return new MongoRedactListsDataService(mongoClient, encryptionService, auditEventPublisher(), redactionCache);
    }

    @Bean
    public ApiKeyDataService apiKeyDataService(final ApiKeyCache apiKeyCache) {
        return new MongoApiKeyDataService(mongoClient, auditEventPublisher(), apiKeyCache);
    }

    @Bean
    public SignInChallengeDataService signInChallengeDataService() {
        return new MongoSignInChallengeDataService(mongoClient);
    }

    @Bean
    public PendingDocumentDataService pendingDocumentDataService(final EncryptionService encryptionService) {
        return new MongoPendingDocumentDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public WebhookDeliveryDataService webhookDeliveryDataService(final EncryptionService encryptionService) {
        return new MongoWebhookDeliveryDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public PolicyVersionDataService policyVersionDataService(final EncryptionService encryptionService) {
        return new MongoPolicyVersionDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public PolicyDataService policyDataService(final Gson gson, final RedactionCache redactionCache,
                                               final EncryptionService encryptionService,
                                               final PolicyVersionDataService policyVersionDataService) {
        return new MongoPolicyDataService(mongoClient, encryptionService, auditEventPublisher(), gson, policyVersionDataService,
                redactionCache);
    }

    @Bean
    public AdminSettingsDataService adminSettingsDataService(final EncryptionService encryptionService) {
        return new MongoAdminSettingsDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public SigningKeyDataService signingKeyDataService(final EncryptionService encryptionService) {
        return new MongoSigningKeyDataService(mongoClient, encryptionService, auditEventPublisher());
    }

    @Bean
    public UserService userService(final EncryptionService encryptionService) {
        return new MongoUserService(mongoClient, encryptionService, auditEventPublisher());
    }

}
