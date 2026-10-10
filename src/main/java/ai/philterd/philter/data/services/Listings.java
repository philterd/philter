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

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Paging, sorting, and searching shared by the API's listings, so every listing pages, counts, and
 * orders the same way.
 */
public final class Listings {

    private Listings() {
    }

    /** One page of a listing and how many items the whole listing has. */
    public record Page<E>(List<E> items, long total) {
    }

    /**
     * The order of a listing: a stored field and a direction. Ties are broken by {@code _id} in the same
     * direction, so paging is stable when many items share a value.
     */
    public record Sort(String field, boolean descending) {

        public Bson toBson() {
            return descending ? Sorts.descending(field, "_id") : Sorts.ascending(field, "_id");
        }

    }

    /** Reads one page of the documents matching {@code filter}, in {@code sort} order, and counts them all. */
    public static <E> Page<E> page(final MongoCollection<Document> collection, final Bson filter, final Sort sort,
                                   final int offset, final int limit, final Function<Document, E> mapper) {
        final List<E> items = new ArrayList<>();
        for (final Document document : collection.find(filter).sort(sort.toBson()).skip(offset).limit(limit)) {
            items.add(mapper.apply(document));
        }
        return new Page<>(items, collection.countDocuments(filter));
    }

    /**
     * A filter matching {@code field} containing {@code q}, ignoring case, or {@code null} when there is
     * no search. {@code q} is matched as text, not as a pattern.
     */
    public static Bson contains(final String field, final String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return Filters.regex(field, Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE));
    }

    /** The conditions that are not {@code null}, all of which must match. */
    public static Bson all(final Bson... conditions) {
        final List<Bson> present = new ArrayList<>();
        for (final Bson condition : conditions) {
            if (condition != null) {
                present.add(condition);
            }
        }
        return present.isEmpty() ? new Document() : present.size() == 1 ? present.get(0) : Filters.and(present);
    }

}
