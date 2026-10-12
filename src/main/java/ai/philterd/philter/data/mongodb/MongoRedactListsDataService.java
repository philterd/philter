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
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.model.AuditLogEvent;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;

/** MongoDB implementation of {@link RedactListsDataService}. */
public class MongoRedactListsDataService extends AbstractEncryptedService<RedactListsEntity> implements RedactListsDataService {

    private static final Logger LOGGER = LogManager.getLogger(RedactListsDataService.class);

    private static final String USER_ID_INDEX = "user_id_unique";

    /** The non-unique index earlier builds made on the same key, which would block the unique one. */
    private static final String LEGACY_USER_ID_INDEX = "user_id_1";

    public MongoRedactListsDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "redact_lists", encryptionService, auditEventPublisher);

        // One redact-lists document per user, so two writes for a user that has none cannot each
        // create one. Sparse, so owner-less legacy documents do not block the index. Existing
        // duplicates for a user fail startup.
        for (final Document index : collection.listIndexes()) {
            if (LEGACY_USER_ID_INDEX.equals(index.getString("name")) && !index.getBoolean("unique", false)) {
                try {
                    collection.dropIndex(LEGACY_USER_ID_INDEX);
                } catch (final com.mongodb.MongoCommandException alreadyDropped) {
                    // Another instance starting at the same time dropped it first.
                    if (alreadyDropped.getErrorCode() != 27) throw alreadyDropped;
                }
            }
        }
        ensureIndex(Indexes.ascending("user_id"), new IndexOptions().unique(true).sparse(true).name(USER_ID_INDEX));
    }

    @Override
    public void saveOrUpdate(final String requestId, final String userId, final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact, final String source) {

        final Document set = new Document();
        final Document inc = new Document();
        final StringBuilder details = new StringBuilder();

        if (termsToAlwaysRedact != null) {
            RedactListsDocuments.writeTerms(set, encryptionService, userId, termsField(RedactList.ALWAYS), termsToAlwaysRedact);
            inc.append(revisionField(RedactList.ALWAYS), 1L);
            details.append("alwaysRedact: ").append(termsToAlwaysRedact.size());
        }
        if (termsToNeverRedact != null) {
            RedactListsDocuments.writeTerms(set, encryptionService, userId, termsField(RedactList.NEVER), termsToNeverRedact);
            inc.append(revisionField(RedactList.NEVER), 1L);
            details.append(details.isEmpty() ? "" : ", ").append("neverRedact: ").append(termsToNeverRedact.size());
        }

        if (set.isEmpty()) {
            return;
        }

        collection.updateOne(Filters.eq("user_id", MongoIds.objectId(userId)), new Document("$set", set).append("$inc", inc),
                new UpdateOptions().upsert(true));

        // The always-redact / never-redact lists are security-relevant: they force or suppress
        // redaction regardless of policy, so changes are audited.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                details.toString());

    }

    @Override
    public RedactListsEntity find(final String userId) {

        final Document query = new Document("user_id", MongoIds.objectId(userId));
        final Document document = collection.find(query).first();

        if(document != null) {
            return RedactListsDocuments.fromDocument(document, encryptionService);
        } else {
            return null;
        }

    }

    @Override
    public ListContents findList(final String userId, final RedactList list) {

        final RedactListsEntity entity = find(userId);
        if (entity == null) {
            return new ListContents(List.of(), 0L);
        }
        return list == RedactList.ALWAYS
                ? new ListContents(entity.getTermsToAlwaysRedact(), entity.getAlwaysRedactRevision())
                : new ListContents(entity.getTermsToNeverRedact(), entity.getNeverRedactRevision());

    }

    @Override
    public ListContents replaceList(final String requestId, final String userId, final RedactList list,
                                    final List<String> terms, final Long expectedRevision, final String source) {

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
            written = collection.findOneAndUpdate(filter, update,
                    new FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER));
        } catch (final MongoServerException e) {
            // Expecting revision 0 when the user's document exists at a later revision makes the
            // upsert try to create a second document, which the unique index refuses.
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                return null;
            }
            throw e;
        }

        if (written == null) {
            return null;
        }

        final RedactListsEntity entity = RedactListsDocuments.fromDocument(written, encryptionService);
        final ListContents contents = list == RedactList.ALWAYS
                ? new ListContents(entity.getTermsToAlwaysRedact(), entity.getAlwaysRedactRevision())
                : new ListContents(entity.getTermsToNeverRedact(), entity.getNeverRedactRevision());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                "list: " + list.getName() + ", terms: " + terms.size() + ", revision: " + contents.revision());

        return contents;

    }

    @Override
    protected Document toDocument(final RedactListsEntity entity) {
        return RedactListsDocuments.toDocument(entity, encryptionService);
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
