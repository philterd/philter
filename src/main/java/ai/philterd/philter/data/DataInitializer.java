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
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Indexes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DataInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

    private final MongoClient mongoClient;
    private final ContextDataService contextService;
    private final PolicyDataService policyDataService;
    private final UserService userService;

    // Inject the beans Spring has already created
    public DataInitializer(final MongoClient mongoClient, final ContextDataService contextService,
                           final PolicyDataService policyDataService, final UserService userService) {

        this.mongoClient = mongoClient;
        this.contextService = contextService;
        this.policyDataService = policyDataService;
        this.userService = userService;

    }

    // Insert initial data.
    @EventListener(ApplicationReadyEvent.class)
    public void init() throws IOException {

        // Check for the admin user.
        if (userService.findByEmail("admin") == null) {

            LOGGER.info("Creating default admin user");
            userService.createUser(ai.philterd.philter.services.RequestIdGenerator.generate(), "admin", "admin", "admin", contextService, policyDataService, ai.philterd.philter.model.Source.SYSTEM.getSource());


        }

        // Load managed policies from JSON files
        policyDataService.loadAndSaveManagedPolicies();

        // Ensure indexes for the vectors collection. Unlike the other collections, the vector service
        // is constructed per request (it is scoped to a user), so its indexes are created here at
        // startup instead. A follow-up to make the vector service a singleton factory is tracked at
        // https://github.com/philterd/philterd-website/issues/201.
        ensureVectorIndexes();

    }

    private void ensureVectorIndexes() {

        try {
            final var collection = mongoClient.getDatabase("philter").getCollection("vectors");
            // Vector representation lookups query (user_id, context, filter_type); size/eviction
            // checks query (user_id, context).
            collection.createIndex(Indexes.ascending("user_id", "context", "filter_type"));
            collection.createIndex(Indexes.ascending("user_id", "context"));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create indexes on the vectors collection: {}", ex.getMessage());
        }

    }

}