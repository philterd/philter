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
import ai.philterd.philter.data.entities.RedactListsEntity;
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
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Locale;

/**
 * Each user's always-redact and never-redact lists, kept in one document per user. Each list has its
 * own revision, which every write of that list increments, so a client can replace one list on the
 * condition that nobody changed it since the client read it.
 */
public class RedactListsDataService extends AbstractEncryptedService<RedactListsEntity> {

    private static final Logger LOGGER = LogManager.getLogger(RedactListsDataService.class);

    /** The maximum number of terms allowed in each list (always-redact and never-redact). */
    public static final int MAXIMUM_TERMS_PER_LIST = 1000;

    /** The maximum length, in characters, of a single term. */
    public static final int MAXIMUM_TERM_LENGTH = 100;

    /** Why a list write was refused with 409: the list changed since the revision the client gave. */
    public static final String REASON_REDACT_LIST_CHANGED = "redact_list_changed";

    private static final String USER_ID_INDEX = "user_id_unique";

    /** The non-unique index earlier builds made on the same key, which would block the unique one. */
    private static final String LEGACY_USER_ID_INDEX = "user_id_1";

    /** One of the two lists, with the fields that hold its terms and its revision. */
    public enum RedactList {

        ALWAYS("always", "terms_to_always_redact", "always_redact_revision"),
        NEVER("never", "terms_to_never_redact", "never_redact_revision");

        private final String name;
        private final String termsField;
        private final String revisionField;

        RedactList(final String name, final String termsField, final String revisionField) {
            this.name = name;
            this.termsField = termsField;
            this.revisionField = revisionField;
        }

        /** The list's name in the API path: {@code always} or {@code never}. */
        public String getName() {
            return name;
        }

        /** The list named in an API path, or {@code null} for any other name. */
        public static RedactList fromName(final String name) {
            if (name == null) {
                return null;
            }
            for (final RedactList list : values()) {
                if (list.name.equals(name.toLowerCase(Locale.ROOT))) {
                    return list;
                }
            }
            return null;
        }

    }

    /** One list's terms and the revision they are at. */
    public record ListContents(List<String> terms, long revision) {
    }

    public RedactListsDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
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

    /**
     * Writes the given lists for the user, creating the user's document if there is none. A
     * {@code null} list is left as it is. Each list written moves to its next revision, whether or not
     * its terms changed, so a client holding an earlier revision learns that it was written.
     */
    public void saveOrUpdate(final String requestId, final ObjectId userId, final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact, final String source) {

        final Document set = new Document();
        final Document inc = new Document();
        final StringBuilder details = new StringBuilder();

        if (termsToAlwaysRedact != null) {
            RedactListsEntity.writeTerms(set, encryptionService, userId, RedactList.ALWAYS.termsField, termsToAlwaysRedact);
            inc.append(RedactList.ALWAYS.revisionField, 1L);
            details.append("alwaysRedact: ").append(termsToAlwaysRedact.size());
        }
        if (termsToNeverRedact != null) {
            RedactListsEntity.writeTerms(set, encryptionService, userId, RedactList.NEVER.termsField, termsToNeverRedact);
            inc.append(RedactList.NEVER.revisionField, 1L);
            details.append(details.isEmpty() ? "" : ", ").append("neverRedact: ").append(termsToNeverRedact.size());
        }

        if (set.isEmpty()) {
            return;
        }

        collection.updateOne(Filters.eq("user_id", userId), new Document("$set", set).append("$inc", inc),
                new UpdateOptions().upsert(true));

        // The always-redact / never-redact lists are security-relevant: they force or suppress
        // redaction regardless of policy, so changes are audited.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                details.toString());

    }

    public RedactListsEntity find(final ObjectId userId) {

        final Document query = new Document("user_id", userId);
        final Document document = collection.find(query).first();

        if(document != null) {
            return RedactListsEntity.fromDocument(document, encryptionService);
        } else {
            return null;
        }

    }

    /** One of the user's lists and its revision. A user with no saved lists has an empty list at revision 0. */
    public ListContents findList(final ObjectId userId, final RedactList list) {

        final RedactListsEntity entity = find(userId);
        if (entity == null) {
            return new ListContents(List.of(), 0L);
        }
        return list == RedactList.ALWAYS
                ? new ListContents(entity.getTermsToAlwaysRedact(), entity.getAlwaysRedactRevision())
                : new ListContents(entity.getTermsToNeverRedact(), entity.getNeverRedactRevision());

    }

    /**
     * Replaces one of the user's lists, leaving the other as it is, and moves it to its next revision.
     * With an expected revision, the list is written only if it is still at that revision, checked and
     * written in one operation so a concurrent write cannot slip between them.
     *
     * @param expectedRevision The revision the client read, or {@code null} to write unconditionally.
     * @return The list as written, or {@code null} when the list is no longer at the expected revision.
     */
    public ListContents replaceList(final String requestId, final ObjectId userId, final RedactList list,
                                    final List<String> terms, final Long expectedRevision, final String source) {

        final Document set = new Document();
        RedactListsEntity.writeTerms(set, encryptionService, userId, list.termsField, terms);
        final Document update = new Document("$set", set).append("$inc", new Document(list.revisionField, 1L));

        final Bson filter;
        final boolean upsert;
        if (expectedRevision == null) {
            filter = Filters.eq("user_id", userId);
            upsert = true;
        } else if (expectedRevision == 0L) {
            // A list never written is at revision 0, whether or not the user's document exists yet.
            filter = Filters.and(Filters.eq("user_id", userId),
                    Filters.or(Filters.exists(list.revisionField, false), Filters.eq(list.revisionField, 0L)));
            upsert = true;
        } else {
            filter = Filters.and(Filters.eq("user_id", userId), Filters.eq(list.revisionField, expectedRevision));
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

        final RedactListsEntity entity = RedactListsEntity.fromDocument(written, encryptionService);
        final ListContents contents = list == RedactList.ALWAYS
                ? new ListContents(entity.getTermsToAlwaysRedact(), entity.getAlwaysRedactRevision())
                : new ListContents(entity.getTermsToNeverRedact(), entity.getNeverRedactRevision());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                "list: " + list.getName() + ", terms: " + terms.size() + ", revision: " + contents.revision());

        return contents;

    }

}
