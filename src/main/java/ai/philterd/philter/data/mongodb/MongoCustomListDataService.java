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
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredCustomListDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores custom lists in MongoDB, one document per list. */
public class MongoCustomListDataService extends StoredCustomListDataService {

    private static final String NAME_INDEX = "user_id_name_unique";

    private final EncryptionService encryptionService;
    private final MongoRecords<CustomListEntity> records;

    public MongoCustomListDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(auditEventPublisher);
        this.encryptionService = encryptionService;
        this.records = new MongoRecords<>(mongoClient, "custom_lists",
                entity -> CustomListDocuments.toDocument(entity, encryptionService));

        // Custom lists are listed by user and looked up by (user_id, name), which is unique, so two
        // concurrent creates cannot both succeed.
        records.ensureIndex(Indexes.ascending("user_id", "name"), new IndexOptions().unique(true).name(NAME_INDEX));
    }

    @Override
    protected String insert(final CustomListEntity list) {
        return records.insert(list);
    }

    @Override
    protected void update(final CustomListEntity list) {
        records.update(list.getId(), list);
    }

    @Override
    public Map<String, List<String>> findItemsByNames(final String userId, final Collection<String> names) {

        if (userId == null || names == null || names.isEmpty()) {
            return Collections.emptyMap();
        }

        final Bson filter = Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.in("name", names));

        final Map<String, List<String>> itemsByName = new HashMap<>();
        for (final Document document : records.collection.find(filter)) {
            final CustomListEntity entity = CustomListDocuments.fromDocument(document, encryptionService);
            itemsByName.put(entity.getName(), entity.getItems());
        }

        return itemsByName;

    }

    @Override
    public CustomListEntity findOneByName(final String name, final String userId) {

        final Document document = records.collection.find(Filters.and(
                Filters.eq("name", name),
                Filters.eq("user_id", MongoIds.objectId(userId)))).first();

        return document == null ? null : CustomListDocuments.fromDocument(document, encryptionService);

    }

    @Override
    public Listings.Page<CustomListEntity> list(final String userId, final String q, final Listings.Sort sort,
                                                final int offset, final int limit) {
        return MongoListings.page(records.collection, MongoListings.all(userId == null ? null : Filters.eq("user_id", MongoIds.objectId(userId)),
                MongoListings.contains("name", q)), sort, offset, limit,
                document -> CustomListDocuments.fromDocument(document, encryptionService));
    }

    @Override
    public void deleteByName(final String name, final String userId) {
        records.collection.deleteOne(new Document("name", name).append("user_id", MongoIds.objectId(userId)));
    }

}
