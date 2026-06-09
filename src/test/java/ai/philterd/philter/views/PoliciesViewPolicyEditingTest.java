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
package ai.philterd.philter.views;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.data.services.PolicyDataService;
import com.google.gson.Gson;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the validate-then-save behavior behind the Policies JSON editor (issue #200 area 3, #7).
 * The PoliciesView delegates validation and persistence to {@link PolicyDataService}; this exercises
 * that delegated path, which is where the real logic lives. The view's own client-side JSON syntax
 * guard is mirrored here so the syntax-error case is covered too.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PoliciesViewPolicyEditingTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private final Gson gson = new Gson();
    private PolicyDataService policyDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("policies")).thenReturn(mongoCollection);
        policyDataService = new PolicyDataService(mongoClient, auditEventPublisher, gson, org.mockito.Mockito.mock(ai.philterd.philter.data.services.PolicyVersionDataService.class));
    }

    private static final String VALID_SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Test
    void savesAValidPolicy() {
        final ObjectId userId = new ObjectId();

        // Uniqueness check returns no existing policy.
        final FindIterable<Document> findIterable = org.mockito.Mockito.mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        final InsertOneResult insertOneResult = org.mockito.Mockito.mock(InsertOneResult.class);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);

        final ServiceResponse response = policyDataService.create("req", userId,
                VALID_SSN_POLICY, "desc", "notes", "valid-policy", "ui");

        assertTrue(response.isSuccessful());
        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void rejectsPolicyWithoutIdentifiersAndDoesNotSave() {
        final ObjectId userId = new ObjectId();

        final FindIterable<Document> findIterable = org.mockito.Mockito.mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        // A policy with no identifiers is invalid and must never be persisted.
        final ServiceResponse response = policyDataService.create("req", userId,
                "{}", "desc", "notes", "bad-policy", "ui");

        assertFalse(response.isSuccessful());
        verify(mongoCollection, never()).insertOne(any(Document.class));
    }

    @Test
    void invalidJsonIsRejectedBeforeSave() {
        final ObjectId userId = new ObjectId();

        final FindIterable<Document> findIterable = org.mockito.Mockito.mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        // Malformed JSON: the service's validatePolicy() reports a syntax error and nothing is saved.
        final ServiceResponse response = policyDataService.create("req", userId,
                "{not valid json", "desc", "notes", "syntax-policy", "ui");

        assertFalse(response.isSuccessful());
        verify(mongoCollection, never()).insertOne(any(Document.class));
    }

}
