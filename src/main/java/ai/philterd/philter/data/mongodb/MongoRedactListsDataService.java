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
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.services.StoredRedactListsDataService;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoServerException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;
import java.util.Map;

/** Stores each user's redact lists in MongoDB, as one document per user. */
public class MongoRedactListsDataService extends StoredRedactListsDataService {

    private static final String USER_ID_INDEX = "user_id_unique";

    private final EncryptionService encryptionService;
    private final MongoRecords<RedactListsEntity> records;

    public MongoRedactListsDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                       final AuditEventPublisher auditEventPublisher, final RedactionCache redactionCache) {
        super(auditEventPublisher, redactionCache);
        this.encryptionService = encryptionService;
        this.records = new MongoRecords<>(mongoClient, "redact_lists",
                entity -> RedactListsDocuments.toDocument(entity, encryptionService));

        // One redact-lists document per user, so two writes for a user that has none cannot each create one.
        records.ensureIndex(Indexes.ascending("user_id"), new IndexOptions().unique(true).sparse(true).name(USER_ID_INDEX));
    }

    @Override
    protected void write(final String userId, final Map<RedactList, List<String>> lists) {

        final Document set = new Document();
        final Document inc = new Document();
        lists.forEach((list, terms) -> {
            RedactListsDocuments.writeTerms(set, encryptionService, userId, termsField(list), terms);
            inc.append(revisionField(list), 1L);
        });

        records.collection.updateOne(Filters.eq("user_id", MongoIds.objectId(userId)),
                new Document("$set", set).append("$inc", inc), new UpdateOptions().upsert(true));

    }

    @Override
    protected ListContents writeIfAt(final String userId, final RedactList list, final List<String> terms,
                                     final Long expectedRevision) {

        final Document set = new Document();
        RedactListsDocuments.writeTerms(set, encryptionService, userId, termsField(list), terms);
        final Document update = new Document("$set", set).append("$inc", new Document(revisionField(list), 1L));

        final Bson filter;
        final boolean upsert;
        if (expectedRevision == null) {
            filter = Filters.eq("user_id", MongoIds.objectId(userId));
            upsert = true;
        } else if (expectedRevision == 0L) {
            // A list never written is at revision 0, whether or not the user's document exists yet.
            filter = Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)),
                    Filters.or(Filters.exists(revisionField(list), false), Filters.eq(revisionField(list), 0L)));
            upsert = true;
        } else {
            filter = Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq(revisionField(list), expectedRevision));
            upsert = false;
        }

        final Document written;
        try {
            written = records.collection.findOneAndUpdate(filter, update,
                    new FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER));
        } catch (final MongoServerException e) {
            // Expecting revision 0 when the user's document exists at a later revision makes the
            // upsert try to create a second document, which the unique index refuses.
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                return null;
            }
            throw e;
        }

        return written == null ? null : contents(RedactListsDocuments.fromDocument(written, encryptionService), list);

    }

    @Override
    public RedactListsEntity find(final String userId) {
        final Document document = records.collection.find(new Document("user_id", MongoIds.objectId(userId))).first();
        return document == null ? null : RedactListsDocuments.fromDocument(document, encryptionService);
    }

    @Override
    public Revisions revisions(final String userId) {
        final Document document = records.collection.find(new Document("user_id", MongoIds.objectId(userId)))
                .projection(new Document(revisionField(RedactList.ALWAYS), 1).append(revisionField(RedactList.NEVER), 1))
                .first();
        return document == null ? new Revisions(0, 0) : new Revisions(
                RedactListsDocuments.readRevision(document, revisionField(RedactList.ALWAYS)),
                RedactListsDocuments.readRevision(document, revisionField(RedactList.NEVER)));
    }

    /** The stored field holding a list's encrypted terms. */
    private static String termsField(final RedactList list) {
        return list == RedactList.ALWAYS ? "terms_to_always_redact" : "terms_to_never_redact";
    }

    /** The stored field holding a list's revision. */
    private static String revisionField(final RedactList list) {
        return list == RedactList.ALWAYS ? "always_redact_revision" : "never_redact_revision";
    }

}
