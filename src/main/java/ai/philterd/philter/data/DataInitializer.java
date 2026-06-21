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
        if (userService.findByUsername("admin") == null) {

            LOGGER.info("Creating default admin user");
            // Seed with the default password but require it to be changed on first login. The admin has
            // no email address by default (username "admin", null email).
            userService.createUser(RequestIdGenerator.generate(), "admin", null, "admin", "admin", policyDataService, contextDataService, Source.SYSTEM.getSource(), true);


        }

        // Load managed policies from JSON files
        policyDataService.loadAndSaveManagedPolicies();

        // Seed a caller-supplied API key if one is configured.
        seedBootstrapApiKey();

        // Ensure indexes for the vectors collection. Unlike the other collections, the vector service
        // is constructed per request (it is scoped to a user), so its indexes are created here at
        // startup instead. A follow-up to make the vector service a singleton factory is tracked at
        // https://github.com/philterd/philterd-website/issues/201.
        ensureVectorIndexes();

    }

    /**
     * Seeds the API key supplied in {@code PHILTER_BOOTSTRAP_API_KEY}, if set, so automation and
     * turnkey (marketplace/IaC) deployments have a credential without the interactive UI flow.
     * Authentication stays on; the key is the operator's own secret, bound to the admin user, and
     * idempotent across restarts. Absent or invalid, this is a no-op and Philter behaves as before.
     */
    private void seedBootstrapApiKey() {

        final String envName = ApiKeyDataService.BOOTSTRAP_API_KEY_ENV;
        final String bootstrapKey = System.getenv(envName);

        if (bootstrapKey == null || bootstrapKey.isBlank()) {
            return;
        }

        if (!API_KEY_PATTERN.matcher(bootstrapKey).matches()) {
            // Never log the value; report only that it was rejected.
            LOGGER.error("{} is set but is not a valid API key (expected 'sk_' followed by 32 "
                    + "alphanumeric characters). Ignoring it.", envName);
            return;
        }

        final UserEntity admin = userService.findByUsername("admin");

        if (admin == null) {
            LOGGER.warn("{} is set but the admin user was not found; skipping bootstrap key.", envName);
            return;
        }

        // Only seed when the admin has never had an API key. Counting deleted keys too means that once
        // the admin has created (or even archived) any key of their own, the bootstrap key is never
        // seeded again, and a bootstrap key that was revoked is not recreated on a later restart.
        if (apiKeyDataService.count(admin.getId(), true) > 0) {
            LOGGER.info("{} is set but the admin user already has API key(s) (active or archived); "
                    + "not seeding the bootstrap key.", envName);
            return;
        }

        final boolean created = apiKeyDataService.ensureApiKey(
                RequestIdGenerator.generate(), admin.getId(), bootstrapKey, Source.SYSTEM.getSource());

        if (created) {
            LOGGER.warn("Seeded a bootstrap API key for the admin user from {}. This key is intended "
                    + "for automation; rotate or revoke it in the UI when it is no longer needed.", envName);
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

}