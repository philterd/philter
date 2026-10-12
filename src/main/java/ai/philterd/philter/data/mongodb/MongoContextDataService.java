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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredContextDataService;
import ai.philterd.philter.services.vectors.VectorServices;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores contexts in MongoDB, one document per context. */
public class MongoContextDataService extends StoredContextDataService {

    private static final String NAME_INDEX = "user_id_context_name_unique";
    private static final String SLOT_INDEX = "user_id_slot_unique";

    private final MongoRecords<ContextEntity> records;
    private final MongoCollection<Document> collection;

    public MongoContextDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher,
                                   final ContextEntryDataService contextEntryService, final VectorServices vectorServices) {
        super(auditEventPublisher, contextEntryService, vectorServices);
        this.records = new MongoRecords<>(mongoClient, "contexts", ContextDocuments::toDocument);
        this.collection = records.collection;

        // Context names are unique within an owner. Incompatible schemas fail startup.
        records.ensureIndex(Indexes.ascending("user_id", "context_name"), new IndexOptions().unique(true).name(NAME_INDEX));

        // Each context holds one of its user's numbered slots, so the limit is enforced by this index
        // rather than by a count that a concurrent create can slip past.
        records.ensureIndex(Indexes.ascending("user_id", "slot"), new IndexOptions().unique(true).name(SLOT_INDEX));
    }

    private static Bson named(final String userId, final String contextName) {
        return Filters.and(Filters.eq("context_name", contextName), Filters.eq("user_id", MongoIds.objectId(userId)));
    }

    @Override
    protected String insert(final ContextEntity context) {
        return records.insert(context);
    }

    @Override
    protected Set<Integer> usedSlots(final String userId) {
        final Set<Integer> used = new HashSet<>();
        for (final Document document : collection.find(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.ne("slot", null)))
                .projection(Projections.include("slot"))) {
            used.add(document.getInteger("slot"));
        }
        return used;
    }

    @Override
    protected void updateSettings(final String userId, final String contextName, final Boolean disambiguation,
                                  final String disambiguationScope, final Boolean ledger) {
        final List<Bson> sets = new ArrayList<>();
        if (disambiguation != null) {
            sets.add(Updates.set("disambiguation", disambiguation));
        }
        if (disambiguationScope != null) {
            sets.add(Updates.set("disambiguation_scope", disambiguationScope));
        }
        if (ledger != null) {
            sets.add(Updates.set("ledger", ledger));
        }
        collection.updateOne(named(userId, contextName), Updates.combine(sets));
    }

    @Override
    protected void delete(final String userId, final String contextName) {
        collection.deleteOne(named(userId, contextName));
    }

    @Override
    public ContextEntity findOneByNameAndUserId(final String contextName, final String userId) {
        final Document document = collection.find(named(userId, contextName)).first();
        return document == null ? null : ContextDocuments.fromDocument(document);
    }

    @Override
    public Listings.Page<ContextEntity> list(final String userId, final String q, final Listings.Sort sort,
                                             final int offset, final int limit) {
        return MongoListings.page(collection, MongoListings.all(userId == null ? null : Filters.eq("user_id", MongoIds.objectId(userId)),
                MongoListings.contains("context_name", q)), sort, offset, limit, ContextDocuments::fromDocument);
    }

    @Override
    public int count(final String userId) {
        return (int) collection.countDocuments(userId == null ? new Document() : Filters.eq("user_id", MongoIds.objectId(userId)));
    }

}
