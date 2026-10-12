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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredApiKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Stores API keys in MongoDB, one document per key, kept after it is revoked. */
public class MongoApiKeyDataService extends StoredApiKeyDataService {

    private final MongoRecords<ApiKeyEntity> records;
    private final MongoCollection<Document> collection;

    public MongoApiKeyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache) {
        super(auditEventPublisher, apiKeyCache);
        this.records = new MongoRecords<>(mongoClient, "api_keys", ApiKeyDocuments::toDocument);
        this.collection = records.collection;

        // Authentication looks keys up by hash; listing is scoped to a user and sorted by timestamp.
        records.ensureIndex(Indexes.ascending("api_key_hash", "deleted"));
        records.ensureIndex(Indexes.ascending("user_id", "deleted", "timestamp"));
        // The expiry sweep looks for live session keys.
        records.ensureIndex(Indexes.ascending("session", "deleted"));
    }

    @Override
    protected String insert(final ApiKeyEntity key) {
        return records.insert(key);
    }

    @Override
    protected boolean existsByHash(final String apiKeyHash) {
        return collection.find(new Document("api_key_hash", apiKeyHash)).first() != null;
    }

    @Override
    protected ApiKeyEntity findActiveByHash(final String apiKeyHash) {
        final Document document = collection.find(new Document("api_key_hash", apiKeyHash).append("deleted", false)).first();
        return document == null ? null : ApiKeyDocuments.fromDocument(document);
    }

    @Override
    protected boolean isEndedSessionByHash(final String apiKeyHash) {
        return collection.find(new Document("api_key_hash", apiKeyHash)
                .append("session", true).append("deleted", true)).first() != null;
    }

    @Override
    public ApiKeyEntity findOneById(final String id) {
        if (id == null) {
            return null;
        }
        final Document document = collection.find(new Document("_id", MongoIds.objectId(id)).append("deleted", false)).first();
        return document == null ? null : ApiKeyDocuments.fromDocument(document);
    }

    @Override
    public List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session,
                                               final boolean descending) {
        final List<ApiKeyEntity> keys = new ArrayList<>();
        for (final Document document : collection.find(activeKeys(userId, session))
                .sort(MongoListings.sort(new Listings.Sort("timestamp", descending))).skip(offset).limit(limit)) {
            keys.add(ApiKeyDocuments.fromDocument(document));
        }
        return keys;
    }

    @Override
    public int countBySession(final String userId, final Boolean session) {
        return (int) collection.countDocuments(activeKeys(userId, session));
    }

    private static Document activeKeys(final String userId, final Boolean session) {
        final Document query = new Document("deleted", false).append("user_id", MongoIds.objectId(userId));
        if (session != null) {
            query.append("session", session ? true : new Document("$ne", true));
        }
        return query;
    }

    @Override
    public int count(final String userId, final boolean includeDeleted) {
        final Document query = new Document();
        if (!includeDeleted) {
            query.append("deleted", false);
        }
        if (userId != null) {
            query.append("user_id", MongoIds.objectId(userId));
        }
        return (int) collection.countDocuments(query);
    }

    @Override
    protected boolean setScopes(final String id, final String userId, final Set<String> scopes) {
        return collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(id)),
                        Filters.eq("user_id", MongoIds.objectId(userId)), Filters.ne("deleted", true)),
                new Document("$set", new Document("scopes", new ArrayList<>(scopes)))).getMatchedCount() == 1;
    }

    /** The update that ends a key, removing the sign-in details kept only while a session lasts. */
    private static Document ended(final Date when) {
        return new Document("$set", new Document("deleted", true).append("deleted_at", when))
                .append("$unset", new Document("client_address", "").append("user_agent", ""));
    }

    @Override
    protected void end(final List<String> ids, final String userId, final Date when) {
        collection.updateMany(Filters.and(Filters.in("_id", MongoIds.objectIds(ids)),
                Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("deleted", false)), ended(when));
    }

    @Override
    protected boolean touch(final String id, final Date now, final Date idleExpiresAt) {
        return collection.updateOne(
                Filters.and(Filters.eq("_id", MongoIds.objectId(id)), Filters.eq("session", true),
                        Filters.eq("deleted", false), Filters.gt("expires_at", now), Filters.gt("idle_expires_at", now)),
                new Document("$set", new Document("last_used_at", now).append("idle_expires_at", idleExpiresAt)))
                .getMatchedCount() == 1;
    }

    private static Bson due(final Date now) {
        return Filters.and(Filters.eq("session", true), Filters.eq("deleted", false),
                Filters.or(Filters.lte("expires_at", now), Filters.lte("idle_expires_at", now)));
    }

    @Override
    protected List<ApiKeyEntity> findDueSessionKeys(final String sessionKeyId, final Date now) {
        final List<ApiKeyEntity> keys = new ArrayList<>();
        for (final Document document : collection.find(sessionKeyId == null ? due(now)
                : Filters.and(Filters.eq("_id", MongoIds.objectId(sessionKeyId)), due(now)))) {
            keys.add(ApiKeyDocuments.fromDocument(document));
        }
        return keys;
    }

    @Override
    protected boolean endIfDue(final String id, final Date now) {
        return collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(id)), due(now)), ended(now))
                .getMatchedCount() == 1;
    }

    @Override
    protected List<ApiKeyEntity> findActiveSessionKeys(final String userId, final String keepKeyId) {
        final List<ApiKeyEntity> keys = new ArrayList<>();
        for (final Document document : collection.find(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("session", true), Filters.eq("deleted", false),
                keepKeyId == null ? new Document() : Filters.ne("_id", MongoIds.objectId(keepKeyId))))) {
            keys.add(ApiKeyDocuments.fromDocument(document));
        }
        return keys;
    }

}
