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
package ai.philterd.philter.data.sqlite;

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
import ai.philterd.philter.data.contract.PiiCountDataServiceContract;
import ai.philterd.philter.data.contract.PolicyDataServiceContract;
import ai.philterd.philter.data.contract.PolicyVersionDataServiceContract;
import ai.philterd.philter.data.contract.RedactListsDataServiceContract;
import ai.philterd.philter.data.contract.SignInChallengeDataServiceContract;
import ai.philterd.philter.data.contract.SigningKeyDataServiceContract;
import ai.philterd.philter.data.contract.UserServiceContract;
import ai.philterd.philter.data.contract.WebhookDeliveryDataServiceContract;
import ai.philterd.philter.data.contract.StorageUnderTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Runs every storage contract against the SQLite implementation, on a new database file for each test. */
class SqliteStorageContractIT implements
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
        PiiCountDataServiceContract,
        PolicyDataServiceContract,
        PolicyVersionDataServiceContract,
        RedactListsDataServiceContract,
        SignInChallengeDataServiceContract,
        SigningKeyDataServiceContract,
        UserServiceContract,
        WebhookDeliveryDataServiceContract {

    @TempDir
    Path directory;

    private SqliteStorageUnderTest storage;

    @BeforeEach
    void createStorage() {
        storage = SqliteStorageUnderTest.create(directory.resolve("philter.db").toString());
    }

    @AfterEach
    void closeStorage() {
        storage.close();
    }

    @Override
    public StorageUnderTest storage() {
        return storage;
    }

}
