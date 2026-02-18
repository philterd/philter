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
import ai.philterd.philter.data.entities.GlobalTermsEntity;
import com.mongodb.client.MongoClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.util.List;

public class GlobalTermsDataService extends AbstractService<GlobalTermsEntity> {

    private static final Logger LOGGER = LogManager.getLogger(GlobalTermsDataService.class);

    public GlobalTermsDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "global_terms", auditEventPublisher);
    }

    public void saveOrUpdate(final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact) {

        final GlobalTermsEntity globalTermsEntity = find();

        if(globalTermsEntity == null) {

            // Save a new entity.
            final GlobalTermsEntity newGlobalTermsEntity = new GlobalTermsEntity();
            newGlobalTermsEntity.setTermsToAlwaysRedact(termsToAlwaysRedact);
            newGlobalTermsEntity.setTermsToNeverRedact(termsToNeverRedact);

            save(newGlobalTermsEntity);

        } else {

            // Update the existing lists.
            globalTermsEntity.setTermsToAlwaysRedact(termsToAlwaysRedact);
            globalTermsEntity.setTermsToNeverRedact(termsToNeverRedact);
            update(globalTermsEntity);

        }

    }

    public GlobalTermsEntity find() {

        final Document document = collection.find().first();

        if(document != null) {
            return GlobalTermsEntity.fromDocument(document);
        } else {
            return null;
        }

    }

}
