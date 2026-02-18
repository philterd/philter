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
package ai.philterd.philter.data.services;

import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ApiKeyDataService extends AbstractService<ApiKeyEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyDataService.class);
    private static final String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int API_KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public ApiKeyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "api_keys", auditEventPublisher);
    }

    private String generateApiKey() {

        LOGGER.info("Generating new API key.");

        String apiKey;
        final StringBuilder sb = new StringBuilder(API_KEY_LENGTH);

        do {
            sb.setLength(0); // Reset StringBuilder for retry
            for (int i = 0; i < API_KEY_LENGTH; i++) {
                final int index = SECURE_RANDOM.nextInt(ALPHANUMERIC_CHARS.length());
                sb.append(ALPHANUMERIC_CHARS.charAt(index));
            }
            apiKey = "sk_" + sb.toString();
        } while (doesApiKeyExist(apiKey));

        return apiKey;

    }

    private boolean doesApiKeyExist(final String apiKey) {

        final String apiKeyHash = EncryptionService.hashSha256(apiKey);
        final Document query = new Document("api_key_hash", apiKeyHash);

        final Document document = collection.find(query).first();

        return document != null;

    }

    public ServiceResponse createApiKey(final String requestId, final String source) {

        // Generate an API key.
        final String apiKey = generateApiKey();

        // API keys should always be at least 6 characters, but validate to be safe
        if(apiKey.length() < 6) {
            throw new IllegalStateException("Generated API key is too short: " + apiKey.length() + " characters");
        }

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        apiKeyEntity.setApiKeyPrefix(apiKey.substring(0, 12) + "...");
        apiKeyEntity.setDeleted(false);
        apiKeyEntity.setTimestamp(new Date());
        final ObjectId apiKeyId = save(apiKeyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, source);

        return new ServiceResponse(apiKey, true, 200);

    }

    public ApiKeyEntity findOneByApiKey(final String apiKey) {

        final String apiKeyHash = EncryptionService.hashSha256(apiKey);
        final Document query = new Document("api_key_hash", apiKeyHash).append("deleted", false);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ApiKeyEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public List<ApiKeyEntity> findAll(final int offset, final int limit) {

        final Document query = new Document("deleted", false);

        final FindIterable<Document> documents = collection.find(query).sort(Sorts.ascending("timestamp")).skip(offset).limit(limit);

        final List<ApiKeyEntity> apiKeyEntities = new ArrayList<>();

        for(final Document document : documents) {
            apiKeyEntities.add(ApiKeyEntity.fromDocument(document));
        }

        return apiKeyEntities;

    }

    public int count() {

        final Document query = new Document("deleted", false);

        return (int) collection.countDocuments(query);

    }

    public ServiceResponse deleteByApiKey(final String requestId, final ApiKeyEntity apiKeyEntity, final String source) {

        // API keys are never deleted from the database - just marked as deleted.
        // This is to preserve usage history through audit and request logs.
        apiKeyEntity.setDeleted(true);
        update(apiKeyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, apiKeyEntity.getId(), apiKeyEntity.getId(), source);

        return ServiceResponse.success();

    }

    public long deleteAllByUserId(final String requestId, final ObjectId userId, final String source) {

        final Document query = new Document("user_id", userId).append("deleted", false);
        final FindIterable<Document> documents = collection.find(query);

        long count = 0;

        for (final Document document : documents) {
            deleteByApiKey(requestId, ApiKeyEntity.fromDocument(document), source);
            count++;
        }

        return count;

    }

}
