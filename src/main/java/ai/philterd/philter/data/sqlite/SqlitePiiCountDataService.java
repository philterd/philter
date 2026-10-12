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
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PiiCountDataService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the PII counts {@link SqlitePiiCountAggregatePublisher} records. */
public class SqlitePiiCountDataService implements PiiCountDataService {

    private final SqliteDatabase database;

    public SqlitePiiCountDataService(final SqliteDatabase database) {
        this.database = database;
    }

    private static Date startOf(final LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Override
    public Listings.Page<Aggregate> list(final LocalDate from, final LocalDate to, final String context, final int offset,
                                         final int limit) {
        final List<Object> params = new ArrayList<>();
        String start = null;
        String end = null;
        String named = null;
        if (from != null) {
            start = "bucket_start >= ?";
            params.add(startOf(from));
        }
        if (to != null) {
            end = "bucket_start < ?";
            params.add(startOf(to.plusDays(1)));
        }
        if (context != null) {
            named = "context = ?";
            params.add(context);
        }
        final String where = Sql.where(start, end, named);
        return database.read(connection -> {
            final List<Object> paged = new ArrayList<>(params);
            paged.add(limit);
            paged.add(offset);
            final List<Aggregate> items = new ArrayList<>();
            for (final Object[] row : Sql.query(connection, "SELECT * FROM pii_count_aggregates" + where
                            + " ORDER BY bucket_start DESC, context ASC LIMIT ? OFFSET ?",
                    r -> new Object[]{r.string("context"), r.longValue("bucket_start"), r.longValue("total_documents"),
                            r.date("updated_at")}, paged.toArray())) {
                final Map<String, Long> counts = new LinkedHashMap<>();
                for (final Object[] count : Sql.query(connection, "SELECT pii_type, count FROM pii_counts "
                                + "WHERE context = ? AND bucket_start = ? ORDER BY pii_type",
                        r -> new Object[]{r.string("pii_type"), r.longValue("count")}, row[0], row[1])) {
                    counts.put((String) count[0], (Long) count[1]);
                }
                items.add(new Aggregate((String) row[0], new Date((Long) row[1]).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                        counts, (Long) row[2], (Date) row[3]));
            }
            return new Listings.Page<>(items, Sql.count(connection, "SELECT COUNT(*) AS n FROM pii_count_aggregates" + where,
                    params.toArray()));
        });
    }

}
