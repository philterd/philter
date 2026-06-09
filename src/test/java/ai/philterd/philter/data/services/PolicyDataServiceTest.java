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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private Gson gson = new Gson();

    private PolicyDataService policyDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("policies")).thenReturn(mongoCollection);
        policyDataService = new PolicyDataService(mongoClient, auditEventPublisher, gson, mock(PolicyVersionDataService.class));
    }

    @Test
    void findOneByName() {
        ObjectId userId = new ObjectId();
        String name = "testPolicy";
        Document doc = new Document("_id", new ObjectId())
                .append("name", name)
                .append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        PolicyEntity policy = policyDataService.findOne(name, userId);

        assertNotNull(policy);
        assertEquals(name, policy.getName());
    }

    @Test
    void create() {
        ObjectId userId = new ObjectId();
        String policyJson = validPolicyJson();
        String policyName = "newPolicy";

        // Mock uniqueness check
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        ServiceResponse response = policyDataService.create("req", userId, policyJson, "desc", "notes", policyName, "source");

        assertTrue(response.isSuccessful());
        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void deleteByName() {
        ObjectId userId = new ObjectId();
        String policyName = "testPolicy";

        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        Document doc = new Document("_id", new ObjectId())
                .append("name", policyName)
                .append("user_id", userId);
        when(findIterable.first()).thenReturn(doc);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);
        when(deleteResult.getDeletedCount()).thenReturn(1L);

        ServiceResponse response = policyDataService.deleteByName("req", policyName, userId, Source.API);

        assertTrue(response.isSuccessful());
        verify(mongoCollection).deleteOne(any(Bson.class));
    }

    @Test
    void findAll() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<PolicyEntity> policies = policyDataService.findAll(userId, 0, 10, false);

        assertNotNull(policies);
        assertTrue(policies.isEmpty());
    }

    @Test
    void isPolicyNameUnique() {
        ObjectId userId = new ObjectId();
        String name = "testPolicy";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        assertTrue(policyDataService.isPolicyNameUnique(name, userId));
    }

    private String validPolicyJson() {
        return "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    }

    private Document existingPolicyDocument(final ObjectId policyId, final ObjectId userId) {
        return new Document("_id", policyId)
                .append("name", "existing-policy")
                .append("user_id", userId)
                .append("notes", "existing notes")
                .append("description", "existing description")
                .append("managed", false);
    }

    private Document captureUpdateSet() {
        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), updateCaptor.capture());
        return ((Document) updateCaptor.getValue()).get("$set", Document.class);
    }

    @Test
    void updatePreservesNotesAndDescriptionWhenBlankOrNull() {
        final ObjectId userId = new ObjectId();
        final ObjectId policyId = new ObjectId();

        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(existingPolicyDocument(policyId, userId));
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        // description is blank, notes is null -> both should be preserved.
        final ServiceResponse response = policyDataService.update("req", userId, policyId, validPolicyJson(), "   ", null, "source");

        assertTrue(response.isSuccessful());

        final Document set = captureUpdateSet();
        assertEquals("existing notes", set.getString("notes"));
        assertEquals("existing description", set.getString("description"));
    }

    @Test
    void updateReplacesNotesAndDescriptionWhenProvided() {
        final ObjectId userId = new ObjectId();
        final ObjectId policyId = new ObjectId();

        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(existingPolicyDocument(policyId, userId));
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        // update(requestId, userId, policyId, policyJson, policyDescription, policyNotes, source)
        final ServiceResponse response = policyDataService.update("req", userId, policyId, validPolicyJson(), "new description", "new notes", "source");

        assertTrue(response.isSuccessful());

        final Document set = captureUpdateSet();
        assertEquals("new notes", set.getString("notes"));
        assertEquals("new description", set.getString("description"));
    }

    @Test
    void validatePolicyAcceptsAValidNativePolicy() {
        final PolicyValidation validation = policyDataService.validatePolicy(validPolicyJson());
        assertTrue(validation.isValid());
    }

    @Test
    void validatePolicyAcceptsStaticReplaceWithAValue() {
        final String json = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":"
                + "[{\"strategy\":\"STATIC_REPLACE\",\"staticReplacement\":\"REDACTED\"}]}}}";
        final PolicyValidation validation = policyDataService.validatePolicy(json);
        assertTrue(validation.isValid());
    }

    @Test
    void validatePolicyRejectsAPolicyWithoutIdentifiers() {
        final PolicyValidation validation = policyDataService.validatePolicy("{}");
        assertFalse(validation.isValid());
    }

    @Test
    void validatePolicyRejectsInvalidJson() {
        final PolicyValidation validation = policyDataService.validatePolicy("{not valid json");
        assertFalse(validation.isValid());
    }

    @Test
    void findAllAcrossUsersReturnsEveryPolicyUnscopedByUser() {
        final Document docA = new Document("_id", new ObjectId()).append("name", "alpha").append("user_id", new ObjectId());
        final Document docB = new Document("_id", new ObjectId()).append("name", "beta").append("user_id", new ObjectId());

        final FindIterable<Document> findIterable = mock(FindIterable.class);
        // findAllAcrossUsers must use the no-arg find() (no owner filter).
        when(mongoCollection.find()).thenReturn(findIterable);
        final Iterator<Document> it = List.of(docA, docB).iterator();
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(findIterable.iterator()).thenReturn(cursor);

        final List<PolicyEntity> policies = policyDataService.findAllAcrossUsers();

        assertEquals(2, policies.size());
        assertEquals("alpha", policies.get(0).getName());
        assertEquals("beta", policies.get(1).getName());
        verify(mongoCollection).find();
    }

    @Test
    void findAllAcrossUsersPagedAppliesSortSkipAndLimit() {
        final Document docA = new Document("_id", new ObjectId()).append("name", "alpha").append("user_id", new ObjectId());

        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find()).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        final Iterator<Document> it = List.of(docA).iterator();
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(findIterable.iterator()).thenReturn(cursor);

        final List<PolicyEntity> page = policyDataService.findAllAcrossUsers(25, 25);

        assertEquals(1, page.size());
        assertEquals("alpha", page.get(0).getName());
        verify(findIterable).sort(any());
        verify(findIterable).skip(25);
        verify(findIterable).limit(25);
    }

    @Test
    void countAllAcrossUsersDelegatesToCountDocuments() {
        when(mongoCollection.countDocuments()).thenReturn(42L);
        assertEquals(42, policyDataService.countAllAcrossUsers());
    }

    @Test
    void findManagedPoliciesPagedAppliesFilterSortSkipAndLimit() {
        final Document doc = new Document("_id", new ObjectId()).append("name", "managed-a")
                .append("user_id", new ObjectId()).append("managed", true);

        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        final Iterator<Document> it = List.of(doc).iterator();
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(findIterable.iterator()).thenReturn(cursor);

        final List<PolicyEntity> page = policyDataService.findManagedPolicies(50, 25);

        assertEquals(1, page.size());
        assertEquals("managed-a", page.get(0).getName());
        verify(findIterable).skip(50);
        verify(findIterable).limit(25);
    }

    @Test
    void countManagedPoliciesDelegatesToCountDocuments() {
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(7L);
        assertEquals(7, policyDataService.countManagedPolicies());
    }
}
