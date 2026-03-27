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
package ai.philterd.philter.data;

import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.usage.OpenSearchRedactionsUsageService;
import ai.philterd.philter.services.usage.apirequests.OpenSearchApiRequestsUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DataInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

    private final ContextDataService contextService;
    private final PolicyDataService policyDataService;
    private final UserService userService;
    private final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService;
    private final OpenSearchRedactionsUsageService openSearchRedactionsUsageService;

    // Inject the beans Spring has already created
    public DataInitializer(final ContextDataService contextService, final PolicyDataService policyDataService,
                           final UserService userService,
                           final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService,
                           final OpenSearchRedactionsUsageService openSearchRedactionsUsageService) {

        this.contextService = contextService;
        this.policyDataService = policyDataService;
        this.userService = userService;
        this.openSearchApiRequestsUsageService = openSearchApiRequestsUsageService;
        this.openSearchRedactionsUsageService = openSearchRedactionsUsageService;

    }

    // Insert initial data.
    @EventListener(ApplicationReadyEvent.class)
    public void init() throws IOException {

        // Check for the admin user.
        if (userService.findByEmail("admin") == null) {

            LOGGER.info("Creating default admin user");
            userService.createUser("admin", "admin", "admin", contextService, policyDataService);


        }

        // Load managed policies from JSON files
        policyDataService.loadAndSaveManagedPolicies();

        // Create OpenSearch indexes.
        openSearchApiRequestsUsageService.createIndex();
        openSearchRedactionsUsageService.createIndex();

    }

}