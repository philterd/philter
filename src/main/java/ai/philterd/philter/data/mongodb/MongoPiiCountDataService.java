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

import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PiiCountDataService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the PII count aggregates {@link MongoPiiCountAggregatePublisher} writes. */
public class MongoPiiCountDataService implements PiiCountDataService {

    private final MongoCollection<Document> collection;

    public MongoPiiCountDataService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase("philter").getCollection(MongoPiiCountAggregatePublisher.COLLECTION);
    }

    @Override
    public Listings.Page<Aggregate> list(final LocalDate from, final LocalDate to, final String context,
                                         final int offset, final int limit) {

        final Bson filter = MongoListings.all(
                from == null ? null : Filters.gte("bucket_start", startOf(from)),
                to == null ? null : Filters.lt("bucket_start", startOf(to.plusDays(1))),
                context == null ? null : Filters.eq("context", context));

        final List<Aggregate> items = new ArrayList<>();
        for (final Document document : collection.find(filter)
                .sort(Sorts.orderBy(Sorts.descending("bucket_start"), Sorts.ascending("context", "_id")))
                .skip(offset).limit(limit)) {
            items.add(toAggregate(document));
        }

        return new Listings.Page<>(items, collection.countDocuments(filter));

    }

    private static Date startOf(final LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static Aggregate toAggregate(final Document document) {
        final Map<String, Long> counts = new LinkedHashMap<>();
        final Document stored = document.get("counts", Document.class);
        if (stored != null) {
            stored.forEach((type, count) -> counts.put(type, ((Number) count).longValue()));
        }
        final Number total = document.get("total_documents", Number.class);
        return new Aggregate(document.getString("context"),
                document.getDate("bucket_start").toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                counts, total == null ? 0 : total.longValue(), document.getDate("updated_at"));
    }

}
