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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.SignInChallengeDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;

import java.util.Map;

/**
 * A storage implementation for the contract tests: its services over one empty database, created fresh for each
 * test, plus the few hooks a test needs to observe what the interfaces do not expose. The contract tests use
 * nothing else, so they apply to every implementation.
 */
public interface StorageUnderTest {

    AdminSettingsDataService adminSettings();

    ApiKeyDataService apiKeys();

    ContextDataService contexts();

    ContextEntryDataService contextEntries();

    CustomListDataService customLists();

    LedgerDataService ledger();

    LegalHoldDataService legalHolds();

    PendingDocumentDataService pendingDocuments();

    PolicyDataService policies();

    PolicyVersionDataService policyVersions();

    RedactListsDataService redactLists();

    SignInChallengeDataService signInChallenges();

    SigningKeyDataService signingKeys();

    UserService users();

    WebhookDeliveryDataService webhookDeliveries();

    AuditEventPublisher auditEvents();

    AuditLogService auditLog();

    PiiCountAggregatePublisher piiCounts();

    /**
     * New services over the same database, as another Philter instance would have, for guarantees that must hold
     * across instances.
     */
    StorageUnderTest anotherInstance();

    // Hooks for what the interfaces do not expose.

    /** Moves a session key's idle window and lifetime into the past, as if its time had run out. */
    void expireSessionKey(String keyId);

    /** Moves a sign-in challenge's expiry into the past, as if its time had run out. */
    void expireSignInChallenge(String token);

    /** The PII counts recorded for a context today, by type, with {@code total_documents} for the redactions. */
    Map<String, Long> piiCountsToday(String context);

}
