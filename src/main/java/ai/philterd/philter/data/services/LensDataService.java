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
import ai.philterd.philter.data.entities.LensEntity;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class LensDataService extends AbstractService<LensEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LensDataService.class);

    public LensDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "lenses", auditEventPublisher);
    }

    public LensEntity findGeneralLens() {

        final Document query = new Document("name", "general");

        final Document document = collection.find(query).first();

        if(document != null) {
            return LensEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public LensEntity findOneByName(final String name) {

        LOGGER.info("Finding model by name {}", name);

        final Document query = new Document("name", name);

        final Document document = collection.find(query).first();

        if(document != null) {
            LOGGER.info("Found model by name {}", name);
            return LensEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public List<LensEntity> findAll() {

        final FindIterable<Document> documents = collection.find().sort(Sorts.descending("display_name"));

        final List<LensEntity> modelEntities = new ArrayList<>();

        for(final Document document : documents) {
            modelEntities.add(LensEntity.fromDocument(document));
        }

        return modelEntities;

    }

}
