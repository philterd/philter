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

import ai.philterd.philter.services.usage.apirequests.ApiRequestsUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A service for generating usage reports.
 */
public class UsageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsageService.class);

    private final ApiRequestsUsageService openSearchApiRequestsService;
    private final RedactionsUsageService openSearchRedactionsService;

    public UsageService(final ApiRequestsUsageService openSearchApiRequestsService,
                        final RedactionsUsageService openSearchRedactionsService) {
        
        this.openSearchApiRequestsService = openSearchApiRequestsService;
        this.openSearchRedactionsService = openSearchRedactionsService;

    }

    /**
     * Builds a CSV report of usage for a user.
     * @param previousDays The number of previous days to include in the report.
     * @return A {@link List} of strings containing the CSV report lines.
     */
    public List<String> buildUsageCSV(final int previousDays) throws IOException {

        LOGGER.info("Building usage CSV for previous days {}", previousDays);

        final Map<String, Long> redactions = openSearchRedactionsService.getRedactionsPreviousXDays(previousDays);
        final Map<String, Long> tokens = openSearchRedactionsService.getTokensPreviousXDays(previousDays);
        final Map<String, Long> standardApiRequests = openSearchApiRequestsService.getApiRequestsLastXDays(previousDays);
        final Map<String, Long> premiumApiRequests = openSearchApiRequestsService.getApiRequestsLastXDays(previousDays);
        final Map<String, Long> freeApiRequests = openSearchApiRequestsService.getApiRequestsLastXDays( previousDays);

        final LocalDate cutoffDate = LocalDate.now().minusDays(previousDays);

        // Add all the dates to a set to make sure we look through all of them.
        final Set<String> dates = new TreeSet<>();
        dates.addAll(redactions.keySet());
        dates.addAll(tokens.keySet());
        dates.addAll(standardApiRequests.keySet());
        dates.addAll(premiumApiRequests.keySet());
        dates.addAll(freeApiRequests.keySet());

        final List<String> csv = new ArrayList<>();
        csv.add("date,tokens,redactions,risk_assessments,api_requests,premium_api_requests,free_api_requests\n");

        for(final String date : dates) {

            final LocalDate d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);

            if (d.isAfter(cutoffDate) || d.isEqual(cutoffDate)) {

                final long redactionsForDate = redactions.getOrDefault(date, 0L);
                final long tokensForDate = tokens.getOrDefault(date, 0L);
                final long apiRequestsForDate = standardApiRequests.getOrDefault(date, 0L);
                final long premiumApiRequestsForDate = premiumApiRequests.getOrDefault(date, 0L);
                final long freeApiRequestsForDate = freeApiRequests.getOrDefault(date, 0L);

                csv.add(String.format("%s,%d,%d,%d,%d,%d\n", date, tokensForDate, redactionsForDate, apiRequestsForDate, premiumApiRequestsForDate, freeApiRequestsForDate));

            }

        }

        return csv;

    }

}
