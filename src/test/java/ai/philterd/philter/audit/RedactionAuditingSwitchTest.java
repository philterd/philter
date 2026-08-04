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
package ai.philterd.philter.audit;

import ai.philterd.philter.config.AuditConfig;
import ai.philterd.philter.model.AuditLogEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redaction-activity events are the only audit events on the hot path (two per redaction) and the
 * only unbounded source of growth in the audit log, so a lean deployment can switch them off.
 * Security events must not be switchable: turning off the audit trail is a compliance decision, not
 * a tuning knob.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedactionAuditingSwitchTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    private MongoDBAuditEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("audit_events")).thenReturn(collection);
        when(collection.insertOne(any())).thenReturn(mock(InsertOneResult.class));
        publisher = new MongoDBAuditEventPublisher(mongoClient);
    }

    @AfterEach
    void clearOverride() {
        AuditConfig.setOverrideForTesting(null);
    }

    @Test
    void redactionEventsAreRecordedByDefault() {
        publisher.auditEvent("req", AuditLogEvent.DOCUMENT_REDACTION_COMPLETED, new ObjectId());
        verify(collection).insertOne(any());
    }

    @Test
    void redactionEventsAreSuppressedWhenSwitchedOff() {
        AuditConfig.setOverrideForTesting(false);

        publisher.auditEvent("req", AuditLogEvent.DOCUMENT_REDACTION_INITIATED, new ObjectId());
        publisher.auditEvent("req", AuditLogEvent.DOCUMENT_REDACTION_COMPLETED, new ObjectId());

        verify(collection, never()).insertOne(any());
    }

    @Test
    void securityEventsAreRecordedEvenWhenRedactionAuditingIsOff() {
        AuditConfig.setOverrideForTesting(false);

        // A representative spread: authentication, key lifecycle, evidence access, admin action,
        // account change, and legal holds. None of these may be suppressed.
        final List<AuditLogEvent> security = Arrays.asList(
                AuditLogEvent.API_AUTHENTICATION_FAILED,
                AuditLogEvent.API_IP_BLOCKED,
                AuditLogEvent.API_KEY_CREATED,
                AuditLogEvent.REDACTION_LEDGER_EXPORTED,
                AuditLogEvent.REDACTION_LEDGER_DELETED,
                AuditLogEvent.ADMIN_CROSS_USER_ACCESS,
                AuditLogEvent.USER_ROLE_CHANGED,
                AuditLogEvent.LEGAL_HOLD_SET,
                AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                AuditLogEvent.POLICY_ACTIVATED,
                AuditLogEvent.REDACTION_REVERSED,
                AuditLogEvent.SIGNING_KEY_REGENERATED);

        for (final AuditLogEvent event : security) {
            publisher.auditEvent("req", event, new ObjectId());
        }

        verify(collection, org.mockito.Mockito.times(security.size())).insertOne(any());
    }

    @Test
    void onlyTheTwoPerRedactionEventsAreSwitchable() {
        // Guards the classification itself. Anything added to REDACTION_ACTIVITY becomes suppressible,
        // so a new event landing in that category should be a deliberate decision, not a default.
        final List<AuditLogEvent> switchable = Arrays.stream(AuditLogEvent.values())
                .filter(AuditLogEvent::isRedactionActivity)
                .collect(Collectors.toList());

        assertEquals(2, switchable.size(), "unexpected switchable events: " + switchable);
        assertTrue(switchable.contains(AuditLogEvent.DOCUMENT_REDACTION_INITIATED));
        assertTrue(switchable.contains(AuditLogEvent.DOCUMENT_REDACTION_COMPLETED));
    }

    @Test
    void evidenceAccessAndHoldEventsAreNeverClassifiedAsRedactionActivity() {
        // These are the events an auditor reaches for first; misclassifying one would let a
        // performance setting quietly disable a compliance control.
        for (final AuditLogEvent event : Arrays.asList(
                AuditLogEvent.REDACTION_LEDGER_EXPORTED,
                AuditLogEvent.REDACTION_LEDGER_DELETED,
                AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                AuditLogEvent.ADMIN_CROSS_USER_ACCESS,
                AuditLogEvent.API_AUTHENTICATION_FAILED)) {
            assertFalse(event.isRedactionActivity(), event + " must never be switchable");
        }
    }

}
