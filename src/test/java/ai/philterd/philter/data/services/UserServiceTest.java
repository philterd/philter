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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private ContextDataService contextDataService;

    @Mock
    private PolicyDataService policyDataService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("users")).thenReturn(mongoCollection);
        userService = new UserService(mongoClient, encryptionService, auditEventPublisher);
        // Important: we need to reset since UserService constructor already calls mongoClient.getDatabase and mongoDatabase.getCollection
        // and we might want different behavior in tests.
    }

    @Test
    void findByEmail() {
        String email = "test@example.com";
        Document doc = new Document("_id", new ObjectId()).append("email", email);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        UserEntity user = userService.findByEmail(email);

        assertNotNull(user);
        assertEquals(email, user.getEmail());
    }

    @Test
    void createUser() {
        String email = "new@example.com";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        ObjectId userId = new ObjectId();
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(userId));

        ServiceResponse response = userService.createUser("req", email, "password", "role", policyDataService, "source");

        assertTrue(response.isSuccessful());
        // A default policy is seeded, but no context is created for the new user.
        verify(policyDataService).save(any());
        verify(contextDataService, never()).save(any());
        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_CREATED),
                eq(userId), eq(userId), eq("source"), org.mockito.ArgumentMatchers.contains("role"));
    }

    @Test
    void createUserWithPasswordChangeRequiredPersistsFlag() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        final InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);

        userService.createUser("req", "admin", "admin", "admin", policyDataService, "system", true);

        verify(mongoCollection).insertOne(docCaptor.capture());
        assertTrue(docCaptor.getValue().getBoolean("password_change_required"));
    }

    @Test
    void createUserAssignsAStableHexFpeKey() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        final InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);

        userService.createUser("req", "fpe@example.com", "pw", "user", policyDataService, "system");

        verify(mongoCollection).insertOne(docCaptor.capture());
        final String fpeKey = docCaptor.getValue().getString("fpe_key");
        assertNotNull(fpeKey);
        assertTrue(fpeKey.matches("[0-9a-f]{64}"), "a new user must get a 256-bit hex FPE key");
    }

    @Test
    void ensureFpeKeyReturnsTheExistingKeyWithoutPersisting() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setFpeKey("0123456789abcdef0123456789abcdef");

        final String key = userService.ensureFpeKey(user);

        assertEquals("0123456789abcdef0123456789abcdef", key);
        verify(mongoCollection, never()).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void ensureFpeKeyGeneratesAndPersistsWhenMissing() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        final String key = userService.ensureFpeKey(user);

        assertTrue(key.matches("[0-9a-f]{64}"), "a generated FPE key must be 256-bit hex");
        assertEquals(key, user.getFpeKey(), "the generated key must be set on the entity");
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void changePasswordClearsPasswordChangeRequiredFlag() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setEmail("admin");
        user.setPasswordChangeRequired(true);

        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(mock(com.mongodb.client.result.UpdateResult.class));

        userService.changePassword("req", user, "a-new-password", "webui");

        assertFalse(user.isPasswordChangeRequired());
    }

    @Test
    void passwordMatches() {
        final UserEntity user = new UserEntity();
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("correct-horse"));

        assertTrue(userService.passwordMatches(user, "correct-horse"));
        assertFalse(userService.passwordMatches(user, "wrong"));
        assertFalse(userService.passwordMatches(null, "x"));
    }

    @Test
    void findAll() {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find()).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));

        List<UserEntity> users = userService.findAll(0, 10);

        assertNotNull(users);
        assertTrue(users.isEmpty());
    }

    @Test
    void count() {
        when(mongoCollection.countDocuments()).thenReturn(10L);
        assertEquals(10, userService.count());
    }

    @Test
    void deleteUser() {
        UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        // We need a mock for the database and collection specifically for this test
        // as deleteUser calls mongoClient.getDatabase multiple times. All collections now live
        // in the single "philter" database.
        MongoDatabase mockPhilterDatabase = mock(MongoDatabase.class);
        MongoCollection<Document> mockGenericCollection = mock(MongoCollection.class);

        when(mongoClient.getDatabase("philter")).thenReturn(mockPhilterDatabase);

        when(mockPhilterDatabase.getCollection(anyString())).thenReturn(mockGenericCollection);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        userService.deleteUser("req", user, "source");

        // Verify that the final delete on the 'users' collection happened.
        // In UserService, 'collection' is the 'users' collection from AbstractEncryptedService.
        verify(mongoCollection).deleteOne(any(Bson.class));

        // Verify other deletions happened on the per-user collections in the philter database.
        verify(mockPhilterDatabase, atLeastOnce()).getCollection(anyString());

        // Contexts are shared and must NOT be deleted with the user.
        verify(mockPhilterDatabase, never()).getCollection("contexts");

        // The deletion is audited with the deleted user's id.
        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_DELETED),
                eq(user.getId()), eq(user.getId()), eq("source"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void changePasswordIsAudited() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        userService.changePassword("req", user, "new-password", "source");

        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_PASSWORD_CHANGED),
                eq(user.getId()), eq(user.getId()), eq("source"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void setUserRoleIsAudited() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        userService.setUserRole("req", user, "admin", "source");

        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_ROLE_CHANGED),
                eq(user.getId()), eq(user.getId()), eq("source"), org.mockito.ArgumentMatchers.contains("admin"));
    }
}
