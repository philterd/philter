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
import ai.philterd.philter.data.entities.LensEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.LensDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
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
    private final Gson gson;

    // Inject the beans Spring has already created
    public DataInitializer(final MongoClient mongoClient, final ContextDataService contextService, final AuditEventPublisher auditEventPublisher, final Gson gson) {
        this.mongoClient = mongoClient;
        this.auditEventPublisher = auditEventPublisher;
        this.contextService = contextService;
        this.gson = gson;
    }

    // Insert initial data.
    @EventListener(ApplicationReadyEvent.class)
    public void init() {

        final PolicyDataService policyService = new PolicyDataService(mongoClient, auditEventPublisher, gson);
        // Load managed policies from JSON files
        policyService.loadAndSaveManagedPolicies();

        final LensDataService lensService = new LensDataService(mongoClient, auditEventPublisher);

        if(lensService.findGeneralLens() == null) {

            LOGGER.info("Inserting general lens.");
            final LensEntity defaultLensEntity = new LensEntity();
            defaultLensEntity.setName("general");
            defaultLensEntity.setDescription("General purpose lens for all types of text");
            defaultLensEntity.setDisplayName("general - General purpose lens for all types of text");
            lensService.save(defaultLensEntity);

        }

        if(lensService.findOneByName("healthcare") == null) {

            LOGGER.info("Inserting healthcare lens.");
            final LensEntity defaultLensEntity = new LensEntity();
            defaultLensEntity.setName("healthcare");
            defaultLensEntity.setDescription("Lens tuned for healthcare text");
            defaultLensEntity.setDisplayName("healthcare - Lens tuned for healthcare text");
            lensService.save(defaultLensEntity);

        }

        if(lensService.findOneByName("financial") == null) {

            LOGGER.info("Inserting financial lens.");
            final LensEntity defaultLensEntity = new LensEntity();
            defaultLensEntity.setName("financial");
            defaultLensEntity.setDescription("Lens tuned for financial text");
            defaultLensEntity.setDisplayName("financial - Lens tuned for financial text");
            lensService.save(defaultLensEntity);

        }

        if(lensService.findOneByName("legal") == null) {

            LOGGER.info("Inserting legal lens.");
            final LensEntity defaultLensEntity = new LensEntity();
            defaultLensEntity.setName("legal");
            defaultLensEntity.setDescription("Lens tuned for legal text");
            defaultLensEntity.setDisplayName("legal - Lens tuned for legal text");
            lensService.save(defaultLensEntity);

        }

        if(lensService.findOneByName("news") == null) {

            LOGGER.info("Inserting news lens.");
            final LensEntity defaultLensEntity = new LensEntity();
            defaultLensEntity.setName("news");
            defaultLensEntity.setDescription("Lens tuned for news text");
            defaultLensEntity.setDisplayName("news - Lens tuned for news text");
            lensService.save(defaultLensEntity);

        }

        // Make sure there is a default context.
        final ContextEntity defaultContextEntity = contextService.findOne("default");

        if(defaultContextEntity == null) {
            // Insert the default context.
            LOGGER.info("Inserting default context");
            final ContextEntity contextEntity = new ContextEntity();
            contextEntity.setContextName("default");
            contextService.save(contextEntity);
        }

        // Make sure there is a default policy.
        final PolicyEntity defaultPolicyEntity = policyService.findOne("default");

        if(defaultPolicyEntity == null) {
            // Insert the default policy.
            LOGGER.info("Inserting the default policy");
            final PolicyEntity policyEntity = new PolicyEntity();
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