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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PendingDocumentDataService extends AbstractService<PendingDocumentEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingDocumentDataService.class);

    private static final long DEFAULT_TTL_SECONDS = 7L * 24L * 60L * 60L;

    public PendingDocumentDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "pending_documents", auditEventPublisher);

        final long ttlSeconds = EnvUtils.getLong("PENDING_DOCUMENTS_TTL_SECONDS", DEFAULT_TTL_SECONDS);

        collection.createIndex(
                Indexes.ascending("completed_at"),
                new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));

        LOGGER.info("TTL index on pending_documents.completed_at set to expire after {} seconds.", ttlSeconds);

        // Per-document lookup/delete, the worker's claim scan, and per-user/context counts.
        ensureIndex(Indexes.ascending("user_id", "document_id"));
        ensureIndex(Indexes.ascending("status", "submitted_at"));
        ensureIndex(Indexes.ascending("user_id", "context_name", "status"));
    }

    public PendingDocumentEntity findOneByDocumentIdAndUserId(final String documentId, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("document_id", documentId),
                Filters.eq("user_id", userId)
        );

        final Document document = collection.find(query).first();
        return document != null ? PendingDocumentEntity.fromDocument(document) : null;

    }

    public List<PendingDocumentEntity> findAllByUserId(final ObjectId userId, final int offset, final int limit) {

        final Bson query = Filters.eq("user_id", userId);

        final FindIterable<Document> documents = collection.find(query)
                .sort(Sorts.descending("submitted_at"))
                .skip(offset)
                .limit(limit);

        final List<PendingDocumentEntity> entities = new ArrayList<>();
        for (final Document document : documents) {
            entities.add(PendingDocumentEntity.fromDocument(document));
        }
        return entities;

    }

    public PendingDocumentEntity claimNextPending(final String workerId) {

        final Bson query = Filters.eq("status", PendingDocumentEntity.STATUS_PENDING);

        final Date now = new Date();
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_PROCESSING),
                Updates.set("claimed_by", workerId),
                Updates.set("claimed_at", now),
                Updates.set("started_at", now)
        );

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("submitted_at"))
                .returnDocument(ReturnDocument.AFTER);

        final Document claimed = collection.findOneAndUpdate(query, update, options);
        return claimed != null ? PendingDocumentEntity.fromDocument(claimed) : null;

    }

    public long reclaimStuckJobs(final Date olderThan) {

        final Bson query = Filters.and(
                Filters.eq("status", PendingDocumentEntity.STATUS_PROCESSING),
                Filters.lt("claimed_at", olderThan)
        );

        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_PENDING),
                Updates.unset("claimed_by"),
                Updates.unset("claimed_at"),
                Updates.unset("started_at")
        );

        return collection.updateMany(query, update).getModifiedCount();

    }

    public void markComplete(final ObjectId id, final byte[] output) {

        final Bson query = Filters.eq("_id", id);
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_COMPLETE),
                Updates.set("output", new Binary(output)),
                Updates.set("completed_at", new Date()),
                Updates.unset("input")
        );

        collection.updateOne(query, update);

    }

    public void markFailed(final ObjectId id, final String errorMessage) {

        final Bson query = Filters.eq("_id", id);
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_FAILED),
                Updates.set("error_message", errorMessage),
                Updates.set("completed_at", new Date()),
                Updates.unset("input")
        );

        collection.updateOne(query, update);

    }

    public long deleteByDocumentIdAndUserId(final String documentId, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("document_id", documentId),
                Filters.eq("user_id", userId)
        );

        final DeleteResult result = collection.deleteMany(query);
        return result.getDeletedCount();

    }

    public int countByUserId(final ObjectId userId) {
        return (int) collection.countDocuments(Filters.eq("user_id", userId));
    }

    public int countPendingByUserId(final ObjectId userId) {
        return (int) collection.countDocuments(Filters.and(
                Filters.eq("user_id", userId),
                Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING)
        ));
    }

    public boolean hasOpenJobsForContext(final ObjectId userId, final String contextName) {
        return collection.countDocuments(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context_name", contextName),
                Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING)
        )) > 0;
    }

}
