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

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Indexes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
public class DataInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

    // Matches the API key format enforced by ApiAuthenticationFilter: sk_ plus 32 alphanumerics.
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^sk_[a-zA-Z0-9]{32}$");

    private final MongoClient mongoClient;
    private final PolicyDataService policyDataService;
    private final ContextDataService contextDataService;
    private final UserService userService;
    private final ApiKeyDataService apiKeyDataService;

    // Inject the beans Spring has already created
    public DataInitializer(final MongoClient mongoClient,
                           final PolicyDataService policyDataService, final ContextDataService contextDataService,
                           final UserService userService, final ApiKeyDataService apiKeyDataService) {

        this.mongoClient = mongoClient;
        this.policyDataService = policyDataService;
        this.contextDataService = contextDataService;
        this.userService = userService;
        this.apiKeyDataService = apiKeyDataService;

    }

    // Insert initial data.
    @EventListener(ApplicationReadyEvent.class)
    public void init() throws IOException {

        // Check for the admin user.
        if (userService.findAnyByUsername("admin") == null) {
            LOGGER.info("Creating initial admin user");
            userService.createUser(RequestIdGenerator.generate(), "admin", "admin", policyDataService, contextDataService,
                    Source.SYSTEM.getSource());
        }

        // Load managed policies from JSON files
        policyDataService.loadAndSaveManagedPolicies();

        // Seed a caller-supplied API key if one is configured.
        seedBootstrapApiKey();

        // Ensure indexes for the vectors collection. Unlike the other collections, the vector service
        // is constructed per request (it is scoped to a user), so its indexes are created here at
        // startup instead. A follow-up to make the vector service a singleton factory is tracked at
        // https://github.com/philterd/philter/issues/73.
        ensureVectorIndexes();

    }

    /**
     * Seeds the API key supplied in {@code PHILTER_BOOTSTRAP_API_KEY} onto the admin user, the only way a
     * deployment gets its first credential: nobody signs in to Philter. It is required until the admin
     * has had an API key, so a new deployment cannot start without one, and ignored afterwards, so a
     * revoked bootstrap key is not recreated on a later restart. Its value is never logged.
     */
    private void seedBootstrapApiKey() {

        final String envName = ApiKeyDataService.BOOTSTRAP_API_KEY_ENV;
        final String bootstrapKey = System.getenv(envName);

        final UserEntity admin = userService.findByUsername("admin");

        // Counting deleted keys too, so that once the admin has had any key, the bootstrap key is
        // neither required nor seeded again.
        if (admin == null || apiKeyDataService.count(admin.getId(), true) > 0) {
            if (bootstrapKey != null && !bootstrapKey.isBlank()) {
                LOGGER.info("{} is set but the admin user already has or had an API key; not seeding it.", envName);
            }
            return;
        }

        requireBootstrapApiKey(bootstrapKey);

        final boolean created = apiKeyDataService.ensureApiKey(
                RequestIdGenerator.generate(), admin.getId(), bootstrapKey, Source.SYSTEM.getSource());

        if (created) {
            LOGGER.warn("Seeded a bootstrap API key for the admin user from {}. It holds every scope; create "
                    + "narrower keys with it and revoke it when it is no longer needed.", envName);
        }

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

    static String requireBootstrapApiKey(final String apiKey) {
        if (apiKey == null || !API_KEY_PATTERN.matcher(apiKey).matches()) {
            throw new IllegalStateException("Set " + ApiKeyDataService.BOOTSTRAP_API_KEY_ENV + " to a private API key "
                    + "('sk_' followed by 32 letters and digits) before first startup. It is the administrator's "
                    + "first credential.");
        }
        return apiKey;
    }
}
