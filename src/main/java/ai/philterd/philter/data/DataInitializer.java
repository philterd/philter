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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class DataInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

    private final MongoClient mongoClient;
    private final AuditEventPublisher auditEventPublisher;
    private final ContextDataService contextService;
    private final ApiKeyDataService apiKeyService;
    private final Gson gson;

    // Inject the beans Spring has already created
    public DataInitializer(final MongoClient mongoClient, final ContextDataService contextService, final ApiKeyDataService apiKeyService, final AuditEventPublisher auditEventPublisher, final Gson gson) {
        this.mongoClient = mongoClient;
        this.auditEventPublisher = auditEventPublisher;
        this.contextService = contextService;
        this.apiKeyService = apiKeyService;
        this.gson = gson;
    }

    // Insert initial data.
    @EventListener(ApplicationReadyEvent.class)
    public void init() {

        final PolicyDataService policyService = new PolicyDataService(mongoClient, auditEventPublisher, gson);
        // Load managed policies from JSON files
        policyService.loadAndSaveManagedPolicies();

        // Create a default API key if none exists.
        // This will serve as our default user.
        final ObjectId defaultUserId;
        if (apiKeyService.count(null) == 0) {
            LOGGER.info("Creating default API key");
            apiKeyService.createApiKey("internal", null, "internal");
            // The above call returns a ServiceResponse, we need the ID.
            // Since we know there's only one now:
            defaultUserId = apiKeyService.findAll(null, 0, 1).get(0).getId();
        } else {
            defaultUserId = apiKeyService.findAll(null, 0, 1).get(0).getId();
        }

        // Make sure there is a default context.
        final ContextEntity defaultContextEntity = contextService.findOne("default", defaultUserId);

        if(defaultContextEntity == null) {
            // Insert the default context.
            LOGGER.info("Inserting default context");
            final ContextEntity contextEntity = new ContextEntity();
            contextEntity.setUserId(defaultUserId);
            contextEntity.setContextName("default");
            contextService.save(contextEntity);
        }

        // Make sure there is a default policy.
        final PolicyEntity defaultPolicyEntity = policyService.findOne("default", defaultUserId);

        if(defaultPolicyEntity == null) {
            // Insert the default policy.
            LOGGER.info("Inserting the default policy");
            final PolicyEntity policyEntity = new PolicyEntity();
            policyEntity.setUserId(defaultUserId);
            policyEntity.setName("default");
            policyEntity.setPolicy(SimplifiedPolicy.getDefaultPolicy());
            policyEntity.setCreatedTimestamp(new Date());
            policyEntity.setLastUpdatedTimestamp(new Date());
            policyEntity.setRevision(0);
            policyEntity.setShared(false);
            policyEntity.setManaged(false);
            policyEntity.setDescription("Default policy");
            policyEntity.setNotes("Default policy for new users that can be modified or used as an example.");
            policyService.save(policyEntity);
        }

    }

}