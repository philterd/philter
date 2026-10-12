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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.contract.AdminSettingsDataServiceContract;
import ai.philterd.philter.data.contract.ApiKeyDataServiceContract;
import ai.philterd.philter.data.contract.AuditLogServiceContract;
import ai.philterd.philter.data.contract.ContextDataServiceContract;
import ai.philterd.philter.data.contract.ContextEntryDataServiceContract;
import ai.philterd.philter.data.contract.CustomListDataServiceContract;
import ai.philterd.philter.data.contract.LedgerDataServiceContract;
import ai.philterd.philter.data.contract.LegalHoldDataServiceContract;
import ai.philterd.philter.data.contract.PendingDocumentDataServiceContract;
import ai.philterd.philter.data.contract.PiiCountAggregatePublisherContract;
import ai.philterd.philter.data.contract.PolicyDataServiceContract;
import ai.philterd.philter.data.contract.PolicyVersionDataServiceContract;
import ai.philterd.philter.data.contract.RedactListsDataServiceContract;
import ai.philterd.philter.data.contract.SignInChallengeDataServiceContract;
import ai.philterd.philter.data.contract.SigningKeyDataServiceContract;
import ai.philterd.philter.data.contract.UserServiceContract;
import ai.philterd.philter.data.contract.WebhookDeliveryDataServiceContract;
import ai.philterd.philter.data.contract.StorageUnderTest;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.junit.jupiter.api.BeforeEach;

/** Runs every storage contract against the MongoDB implementation, on a fresh in-memory MongoDB for each test. */
class MongoStorageContractIT extends AbstractMongoIT implements
        AdminSettingsDataServiceContract,
        ApiKeyDataServiceContract,
        AuditLogServiceContract,
        ContextDataServiceContract,
        ContextEntryDataServiceContract,
        CustomListDataServiceContract,
        LedgerDataServiceContract,
        LegalHoldDataServiceContract,
        PendingDocumentDataServiceContract,
        PiiCountAggregatePublisherContract,
        PolicyDataServiceContract,
        PolicyVersionDataServiceContract,
        RedactListsDataServiceContract,
        SignInChallengeDataServiceContract,
        SigningKeyDataServiceContract,
        UserServiceContract,
        WebhookDeliveryDataServiceContract {

    private StorageUnderTest storage;

    @BeforeEach
    void createStorage() {
        storage = new MongoStorageUnderTest(mongoClient);
    }

    @Override
    public StorageUnderTest storage() {
        return storage;
    }

}
