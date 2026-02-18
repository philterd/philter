package ai.philterd.philter.services.usage.apirequests;

import ai.philterd.philter.services.usage.AbstractUsageService;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
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

public class OpenSearchApiRequestsUsageService extends AbstractUsageService implements ApiRequestsUsageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchApiRequestsUsageService.class);

    public OpenSearchApiRequestsUsageService() {
        super(AbstractUsageService.API_REQUESTS_USAGE_INDEX_NAME);
    }

    @Override
    public Map<String, Long> getApiRequestsLastXDays(final int previousDays) throws IOException {

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(previousDays - 1);

        return getApiRequests(startDate, endDate);

    }

    @Override
    public Map<String, Long> getApiRequestsPreviousCalendarMonth() throws IOException {

        final LocalDate now = LocalDate.now();
        final LocalDate startDate = now.minusMonths(1).withDayOfMonth(1);
        final LocalDate endDate = now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth());

        return getApiRequests(startDate, endDate);

    }

    @Override
    public long getCountOfApiRequestsInPreviousCalendarMonth() throws IOException {

        final LocalDate now = LocalDate.now();
        final LocalDate startDate = now.minusMonths(1).withDayOfMonth(1);
        final LocalDate endDate = now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth());

        final SearchRequest searchRequest = SearchRequest.of(s -> s
                .index(API_REQUESTS_USAGE_INDEX_NAME)
                .size(0)
                .query(q -> q
                        .bool(b -> b
                                .filter(f -> f
                                        .range(r -> r
                                                .field("timestamp")
                                                .gte(JsonData.of(toIsoString(startDate)))
                                                .lte(JsonData.of(toEndOfDayIsoString(endDate)))
                                        )
                                )
                                .mustNot(m -> m         // http response codes that are NOT billed
                                        .terms(t -> t
                                                .field("status")
                                                .terms(ts -> ts
                                                        .value(List.of(
                                                                FieldValue.of("500")
                                                        ))
                                                )
                                        )
                                )
                        )
                )
        );

        final SearchResponse<Void> response = client.search(searchRequest, Void.class);

        return response.hits().total().value();

    }

    public Map<String, Long> getApiRequests(final LocalDate startDate, final LocalDate endDate) throws IOException {

        final ZoneId zoneId = ZoneId.systemDefault();

        final Query q = new Query.Builder()
                    .bool(b -> b
                            .filter(f -> f.range(r -> r
                                    .field("timestamp")
                                    .gte(JsonData.of(toIsoString(startDate)))
                                    .lte(JsonData.of(toEndOfDayIsoString(endDate)))))
                            .mustNot(m -> m
                                    .terms(t -> t
                                            .field("status")
                                            .terms(ts -> ts
                                                    .value(List.of(
                                                            FieldValue.of("400"),
                                                            FieldValue.of("500")
                                                    ))
                                            )
                                    )
                            ))
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
                .aggregations("by_day", a -> a.dateHistogram(byDay))
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

                //final ZonedDateTime zdt = ZonedDateTime.parse(b.keyAsString(), DateTimeFormatter.ISO_DATE_TIME);
                //final Date dateWithZone = Date.from(zdt.toInstant());
                //out.put(dateWithZone, b.docCount());

                // Doing substring to trim out just the YYYY-MM-DD
                out.put(b.keyAsString().substring(0, 10), b.docCount());

            }

        }

        return out;

    }

}
