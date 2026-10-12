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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredLegalHoldDataService;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/** Stores legal holds in MongoDB, one document per hold. */
public class MongoLegalHoldDataService extends StoredLegalHoldDataService {

    private final MongoCollection<Document> holds;

    public MongoLegalHoldDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher,
                                     final EvidenceGuard guard) {
        super(auditEventPublisher, guard);

        final MongoRecords<LegalHoldEntity> records = new MongoRecords<>(mongoClient, "legal_holds", LegalHoldDocuments::toDocument);
        holds = records.collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);

        // Fast lookup by owner + reference (uniqueness constraint).
        holds.createIndex(Indexes.ascending("user_id", "reference"), new IndexOptions().unique(true));
        // Hold-check queries: is any hold active for this user?
        records.ensureIndex(Indexes.ascending("user_id", "scope_type", "scope_value"));
    }

    @Override
    protected void insert(final LegalHoldEntity hold) {
        try {
            holds.insertOne(LegalHoldDocuments.toDocument(hold));
        } catch (final com.mongodb.MongoWriteException ex) {
            throw MongoRecords.isDuplicate(ex) ? new ai.philterd.philter.data.services.DuplicateRecordException(ex) : ex;
        }
    }

    @Override
    protected void delete(final String reference, final String userId) {
        holds.deleteOne(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("reference", reference)));
    }

    @Override
    public LegalHoldEntity findByReference(final String reference, final String userId) {
        final Document doc = holds.find(Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("reference", reference))).first();
        return doc != null ? LegalHoldDocuments.fromDocument(doc) : null;
    }

    @Override
    public Listings.Page<LegalHoldEntity> list(final String userId, final String q, final Listings.Sort sort,
                                               final int offset, final int limit) {
        return MongoListings.page(holds, MongoListings.all(userId == null ? null : Filters.eq("user_id", MongoIds.objectId(userId)),
                MongoListings.contains("reference", q)), sort, offset, limit, LegalHoldDocuments::fromDocument);
    }

    @Override
    public List<LegalHoldEntity> findBlockingHoldsForDocument(final String userId, final String documentId) {
        return toList(holds.find(Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.or(
                        Filters.and(
                                Filters.eq("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN),
                                Filters.eq("scope_value", documentId)),
                        Filters.eq("scope_type", LegalHoldEntity.SCOPE_USER)))));
    }

    @Override
    public List<LegalHoldEntity> findAllHoldsForUser(final String userId) {
        return toList(holds.find(Filters.eq("user_id", MongoIds.objectId(userId))));
    }

    private static List<LegalHoldEntity> toList(final Iterable<Document> docs) {
        final List<LegalHoldEntity> result = new ArrayList<>();
        for (final Document doc : docs) {
            result.add(LegalHoldDocuments.fromDocument(doc));
        }
        return result;
    }

}
