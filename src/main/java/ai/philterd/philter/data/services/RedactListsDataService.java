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
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Indexes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.List;

public class RedactListsDataService extends AbstractService<RedactListsEntity> {

    private static final Logger LOGGER = LogManager.getLogger(RedactListsDataService.class);

    /** The maximum number of terms allowed in each list (always-redact and never-redact). */
    public static final int MAXIMUM_TERMS_PER_LIST = 1000;

    /** The maximum length, in characters, of a single term. */
    public static final int MAXIMUM_TERM_LENGTH = 100;

    public RedactListsDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "redact_lists", auditEventPublisher);

        // One redact-lists document per user, always looked up by user_id.
        ensureIndex(Indexes.ascending("user_id"));
    }

    public void saveOrUpdate(final String requestId, final ObjectId userId, final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact, final String source) {

        final RedactListsEntity redactListsEntity = find(userId);

        if(redactListsEntity == null) {

            // Save a new entity.
            final RedactListsEntity newRedactListsEntity = new RedactListsEntity();
            newRedactListsEntity.setUserId(userId);
            newRedactListsEntity.setTermsToAlwaysRedact(termsToAlwaysRedact);
            newRedactListsEntity.setTermsToNeverRedact(termsToNeverRedact);

            save(newRedactListsEntity);

        } else {

            // Update the existing lists.
            redactListsEntity.setTermsToAlwaysRedact(termsToAlwaysRedact);
            redactListsEntity.setTermsToNeverRedact(termsToNeverRedact);
            update(redactListsEntity);

        }

        // The always-redact / never-redact lists are security-relevant: they force or suppress
        // redaction regardless of policy, so changes are audited.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                "alwaysRedact: " + termsToAlwaysRedact.size() + ", neverRedact: " + termsToNeverRedact.size());

    }

    public RedactListsEntity find(final ObjectId userId) {

        final Document query = new Document("user_id", userId);
        final Document document = collection.find(query).first();

        if(document != null) {
            return RedactListsEntity.fromDocument(document);
        } else {
            return null;
        }

    }

}
