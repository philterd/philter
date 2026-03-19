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
package ai.philterd.philter.services.usage;

import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.CalendarInterval;
import org.opensearch.client.opensearch._types.aggregations.DateHistogramAggregation;
import org.opensearch.client.opensearch._types.aggregations.DateHistogramBucket;
import org.opensearch.client.opensearch._types.aggregations.FieldDateMath;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenSearchRedactionsUsageService extends AbstractUsageService implements RedactionsUsageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchRedactionsUsageService.class);

    public OpenSearchRedactionsUsageService() {
        super(AbstractUsageService.REDACTIONS_USAGE_INDEX_NAME);
    }

    @Override
    public Map<String, Long> getTokensPreviousXDays(final int previousDays) throws IOException {

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(previousDays - 1);

        return getTokens(startDate, endDate);

    }

    @Override
    public Map<String, Long> getTokensPreviousCalendarMonth() throws IOException {

        final LocalDate now = LocalDate.now();
        final LocalDate startDate = now.minusMonths(1).withDayOfMonth(1);
        final LocalDate endDate = now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth());

        return getTokens(startDate, endDate);

    }

    @Override
    public Map<String, Long> getTokens(final LocalDate startDate, final LocalDate endDate) throws IOException {

        return getData(startDate, endDate, "tokens");

    }

    @Override
    public Map<String, Long> getRedactionsPreviousXDays(final int previousDays) throws IOException {

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(previousDays - 1);

        return getRedactions(startDate, endDate);

    }

    @Override
    public Map<String, Long> getRedactionsPreviousCalendarMonth() throws IOException {

        final LocalDate now = LocalDate.now();
        final LocalDate startDate = now.minusMonths(1).withDayOfMonth(1);
        final LocalDate endDate = now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth());

        return getRedactions(startDate, endDate);

    }

    @Override
    public Map<String, Long> getRedactions(final LocalDate startDate, final LocalDate endDate) throws IOException {

        return getData(startDate, endDate, "redactions");

    }

    private Map<String, Long> getData(final LocalDate startDate, final LocalDate endDate, final String fieldToSum) throws IOException {

        final ZoneId zoneId = ZoneId.systemDefault();

        final Query q = new Query.Builder()
                .bool(b -> b
                        .filter(f -> f.range(r -> r
                                .field("timestamp")
                                .gte(JsonData.of(toIsoString(startDate)))
                                .lte(JsonData.of(toEndOfDayIsoString(endDate))))))
                .build();

        final DateHistogramAggregation byDay = new DateHistogramAggregation.Builder()
                .field("timestamp")
                .calendarInterval(CalendarInterval.Day)
                .timeZone(zoneId.getId())
                .minDocCount(0)
                .extendedBounds(eb -> eb
                        .min(FieldDateMath.of(fdm -> fdm.expr(toIsoString(startDate))))
                        .max(FieldDateMath.of(fdm -> fdm.expr(toEndOfDayIsoString(endDate)))))
                .build();

        final SearchRequest req = new SearchRequest.Builder()
                .index(indexName)
                .size(0)
                .query(q)
                .aggregations("by_day", a -> a
                        .dateHistogram(byDay)
                        .aggregations("total", sa -> sa.sum(s -> s.field(fieldToSum))))
                .build();

        final SearchResponse<Void> resp = client.search(req, Void.class);

        final Aggregate agg = resp.aggregations().get("by_day");
        if (agg == null || agg.dateHistogram() == null) {
            return Map.of();
        }

        final List<DateHistogramBucket> buckets = agg.dateHistogram().buckets().array();
        final Map<String, Long> out = new LinkedHashMap<>(buckets.size());

        for (final DateHistogramBucket b : buckets) {

            if(b.keyAsString() != null) {

                final long tokens = (long) b.aggregations().get("total").sum().value();

                // Doing substring to trim out just the YYYY-MM-DD
                out.put(b.keyAsString().substring(0, 10), tokens);

            }

        }

        return out;

    }

}
