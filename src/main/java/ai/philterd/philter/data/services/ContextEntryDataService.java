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

import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ContextEntryDataService extends AbstractService<ContextEntryEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextEntryDataService.class);

    public static final int MAX_LIMIT = 100;

    private static final Pattern UUID_REGEX_PATTERN = Pattern.compile(
            "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"
    );

    public ContextEntryDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "context_entries", auditEventPublisher);
    }

    public List<ContextEntryEntity> findAllByUserIdAndContext(final ObjectId userId, final String contextName, int limit) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final Document query = new Document("user_id", userId).append("context_name", contextName);

        final Iterable<Document> documents = collection.find(query).limit(effectiveLimit);

        final List<ContextEntryEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {
            contextEntities.add(ContextEntryEntity.fromDocument(document));
        }

        return contextEntities;

    }

    public boolean containsToken(final ObjectId userId, final String contextName, final String token) {

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("token_hash", tokenHash);
        final Document document = collection.find(query).first();
        return document != null;

    }

    public boolean containsReplacement(final ObjectId userId, final String contextName, final String replacement) {

        // Replacement is not encrypted because it is not PII/PHI.

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("replacement", replacement);
        final Document document = collection.find(query).first();
        return document != null;

    }

    public String getReplacement(final ObjectId userId, final String contextName, final String token) {

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("token_hash", tokenHash);
        final Document document = collection.find(query).first();

        if(document != null) {

            final ContextEntryEntity contextEntryEntity = ContextEntryEntity.fromDocument(document);

            // Increment the number of reads for this document.
            LOGGER.info("Incrementing the reads for the context entry {}", contextEntryEntity.getId());
            contextEntryEntity.setReads(contextEntryEntity.getReads() + 1);
            update(contextEntryEntity);

            return contextEntryEntity.getReplacement();

        } else {
            return null;
        }

    }

    @Override
    public ObjectId save(final ContextEntryEntity contextEntryEntity) {
        throw new UnsupportedOperationException("Use putReplacement() instead.");
    }

    public void putReplacement(final ObjectId userId, final String contextName, final String token, final String replacement, final String filterType) {

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        // Check to see if this token already exists in the context.
        if(!containsToken(userId, contextName, token)) {

            final ContextEntryEntity contextEntryEntity = new ContextEntryEntity();
            contextEntryEntity.setUserId(userId);
            contextEntryEntity.setContextName(contextName);
            contextEntryEntity.setTokenHash(tokenHash);
            contextEntryEntity.setReplacement(replacement);
            contextEntryEntity.setReads(0L);
            contextEntryEntity.setTimestamp(new Date());
            contextEntryEntity.setFilterType(filterType);

            // Does the replacement match the regex for a UUID?
            contextEntryEntity.setReplacementUuid(UUID_REGEX_PATTERN.matcher(replacement).matches());

            // TOOD: Check the size of the context against ContextEntity.MAX_CONTEXT_SIZE.
            // If it exceeds the max, remove the context entry that has been used the least (per the `reads``).

            collection.insertOne(contextEntryEntity.toDocument());

        }

    }

    public void deleteByContextName(final String contextName) {

        // Safeguard to prevent deleting the default context.
        if(!"default".equalsIgnoreCase(contextName)) {

            final Document filter = new Document("context_name", contextName);
            collection.deleteMany(filter);

        }

    }

    public Map<String, Long> getFilterTypeCounts(final String contextName) {

        final Document matchStage = new Document("$match",
                new Document("context_name", contextName).append("replacement_uuid", false));

        final Document groupStage = new Document("$group",
                new Document("_id", "$filter_type")
                        .append("count", new Document("$sum", 1)));

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(matchStage);
        pipeline.add(groupStage);

        final Map<String, Long> filterTypeCounts = new HashMap<>();

        for (final Document document : collection.aggregate(pipeline)) {
            final String filterType = document.getString("_id");
            final Integer count = document.getInteger("count");
            if (filterType != null && count != null) {
                filterTypeCounts.put(filterType, count.longValue());
            }
        }

        return filterTypeCounts;

    }

}