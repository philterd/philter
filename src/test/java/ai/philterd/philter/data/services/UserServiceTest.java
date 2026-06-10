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
import ai.philterd.philter.testutil.TestEncryptionService;
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

    // A real encryption service (not a mock) so toDocument/fromDocument round-trip the now-encrypted
    // per-user secrets (fpe key, webhook secret, MFA secret).
    private final EncryptionService encryptionService = new TestEncryptionService();

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
        // The constructor interacts with the mocks (getDatabase/getCollection and a one-time username
        // backfill that calls find()); clear those interactions so each test's verifies count only the
        // calls that test makes.
        clearInvocations(mongoCollection);
    }

    @Test
    void findByUsername() {
        String email = "test@example.com";
        Document doc = new Document("_id", new ObjectId()).append("email", email);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        UserEntity user = userService.findByUsername(email);

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

        ServiceResponse response = userService.createUser("req", email, "password", "role", policyDataService, contextDataService, "source");

        assertTrue(response.isSuccessful());
        // A default policy and a default context are seeded for the new user.
        verify(policyDataService).save(any());
        verify(contextDataService).create("default", userId);
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

        userService.createUser("req", "admin", "admin", "admin", policyDataService, contextDataService, "system", true);

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

        userService.createUser("req", "fpe@example.com", "pw", "user", policyDataService, contextDataService, "system");

        verify(mongoCollection).insertOne(docCaptor.capture());
        // The FPE key is encrypted at rest, so decrypt it back through the entity to assert its form.
        final String fpeKey = UserEntity.fromDocument(docCaptor.getValue(), encryptionService).getFpeKey();
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
    void enableMfaSetsSecretEnrolledAndResetsLock() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setMfaLocked(true);
        user.setMfaFailedAttempts(3);

        userService.enableMfa("req", user, "SECRET32", "system");

        assertTrue(user.isMfaEnabled());
        assertEquals("SECRET32", user.getMfaSecret());
        assertFalse(user.isMfaLocked());
        assertEquals(0, user.getMfaFailedAttempts());
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void disableMfaClearsEnrollmentAndLock() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setMfaEnabled(true);
        user.setMfaSecret("SECRET32");
        user.setMfaLocked(true);
        user.setMfaFailedAttempts(5);

        userService.disableMfa("req", user, "system");

        assertFalse(user.isMfaEnabled());
        assertNull(user.getMfaSecret());
        assertFalse(user.isMfaLocked());
        assertEquals(0, user.getMfaFailedAttempts());
    }

    @Test
    void recordFailedMfaAttemptLocksOnTheLimit() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setMfaEnabled(true);

        boolean locked = false;
        for (int i = 0; i < UserService.MAX_MFA_ATTEMPTS; i++) {
            assertFalse(user.isMfaLocked(), "must not be locked before the limit");
            locked = userService.recordFailedMfaAttempt("req", user, "system");
        }

        assertTrue(locked, "the limit-th failure must lock the account");
        assertTrue(user.isMfaLocked());
        assertEquals(UserService.MAX_MFA_ATTEMPTS, user.getMfaFailedAttempts());
    }

    @Test
    void recordFailedMfaAttemptBelowLimitDoesNotLock() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        final boolean locked = userService.recordFailedMfaAttempt("req", user, "system");

        assertFalse(locked);
        assertFalse(user.isMfaLocked());
        assertEquals(1, user.getMfaFailedAttempts());
    }

    @Test
    void unlockMfaClearsLockAndCounterButKeepsEnrollment() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setMfaEnabled(true);
        user.setMfaSecret("SECRET32");
        user.setMfaLocked(true);
        user.setMfaFailedAttempts(UserService.MAX_MFA_ATTEMPTS);

        userService.unlockMfa("req", user, "system");

        assertFalse(user.isMfaLocked());
        assertEquals(0, user.getMfaFailedAttempts());
        assertTrue(user.isMfaEnabled(), "unlock must not change enrollment");
        assertEquals("SECRET32", user.getMfaSecret());
    }

    @Test
    void resetMfaAttemptsClearsCounter() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setMfaFailedAttempts(3);

        userService.resetMfaAttempts(user);

        assertEquals(0, user.getMfaFailedAttempts());
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
    void deactivateUser() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        userService.deactivateUser("req", user, "source");

        // The user row is retained and updated (marked deactivated), never hard-removed. In
        // UserService, 'collection' is the 'users' collection.
        verify(mongoCollection, never()).deleteOne(any(Bson.class));
        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), updateCaptor.capture());
        final Document set = ((Document) updateCaptor.getValue()).get("$set", Document.class);
        assertTrue(set.getBoolean("deactivated"), "the user must be marked deactivated");
        assertNotNull(set.getDate("deactivated_at"), "the deactivation time must be recorded");
        // The in-memory entity reflects what was persisted.
        assertTrue(user.isDeactivated());
        assertNotNull(user.getDeactivatedAt());

        // Deactivation does not touch the user's data: no other collections are deleted from, and no
        // contexts are removed.
        verify(contextDataService, never()).deleteByName(anyString(), any());

        // The deactivation is audited with the user's id, and the detail records that evidence
        // (policies and the redaction ledger) was retained rather than cascaded.
        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_DEACTIVATED),
                eq(user.getId()), eq(user.getId()), eq("source"), org.mockito.ArgumentMatchers.contains("retained"));
    }

    @Test
    void deactivateUserIsIdempotent() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setDeactivated(true);

        userService.deactivateUser("req", user, "source");

        // Already deactivated: nothing is written and nothing is audited again.
        verify(mongoCollection, never()).updateOne(any(Bson.class), any(Bson.class));
        verify(auditEventPublisher, never()).auditEvent(any(), eq(ai.philterd.philter.model.AuditLogEvent.USER_DEACTIVATED),
                any(), any(), any(), any());
    }

    @Test
    void reactivateUser() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setDeactivated(true);
        user.setDeactivatedAt(new java.util.Date());

        userService.reactivateUser("req", user, "source");

        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), updateCaptor.capture());
        final Document set = ((Document) updateCaptor.getValue()).get("$set", Document.class);
        assertFalse(set.getBoolean("deactivated"), "the user must be marked active again");
        assertNull(set.get("deactivated_at"), "the deactivation time must be cleared");
        assertFalse(user.isDeactivated());
        assertNull(user.getDeactivatedAt());

        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.USER_REACTIVATED),
                eq(user.getId()), eq(user.getId()), eq("source"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void reactivateUserIsIdempotent() {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        // Not deactivated.

        userService.reactivateUser("req", user, "source");

        verify(mongoCollection, never()).updateOne(any(Bson.class), any(Bson.class));
        verify(auditEventPublisher, never()).auditEvent(any(), eq(ai.philterd.philter.model.AuditLogEvent.USER_REACTIVATED),
                any(), any(), any(), any());
    }

    @Test
    void findByUsernameExcludesDeactivatedUsers() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        final ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        when(mongoCollection.find(filterCaptor.capture())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        userService.findByUsername("gone@example.com");

        // The query must constrain on both the email and the deactivated flag so a deactivated user is
        // never returned (and therefore cannot sign in or be targeted via the owner parameter).
        final org.bson.BsonDocument filter = filterCaptor.getValue()
                .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(filter.containsKey("$and"), "findByUsername must combine the email and deactivated constraints");
        assertTrue(filter.toJson().contains("deactivated"), "findByUsername must filter out deactivated users");
    }

    @Test
    void isDeactivatedReturnsTrueForADeactivatedUser() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("deactivated", true));

        assertTrue(userService.isDeactivated(new ObjectId()));
    }

    @Test
    void isDeactivatedTreatsAMissingUserAsDeactivated() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        assertTrue(userService.isDeactivated(new ObjectId()), "a missing user holds no active access");
    }

    @Test
    void createUserRejectsAnEmailReservedByADeactivatedAccount() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("_id", new ObjectId())
                .append("email", "taken@example.com").append("deactivated", true));

        final ServiceResponse response = userService.createUser("req", "taken@example.com", "pw", "user",
                policyDataService, contextDataService, "system");

        assertFalse(response.isSuccessful());
        verify(mongoCollection, never()).insertOne(any(Document.class));
    }

    @Test
    void countExcludesDeactivatedWhenRequested() {
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(7L);
        assertEquals(7, userService.count(false));
        verify(mongoCollection).countDocuments(any(Bson.class));
        verify(mongoCollection, never()).countDocuments();
    }

    @Test
    void findAllExcludesDeactivatedWhenRequested() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));

        userService.findAll(0, 10, false);

        // Excluding deactivated users issues a filtered find(), not the unfiltered find().
        verify(mongoCollection).find(any(Bson.class));
        verify(mongoCollection, never()).find();
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
