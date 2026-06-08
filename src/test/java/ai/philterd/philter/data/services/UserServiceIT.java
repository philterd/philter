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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.KeyProvider;
import ai.philterd.philter.services.encryption.KeyResponse;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link UserService} against a real (in-memory) MongoDB. These exercise the
 * full encrypted-service stack — a real {@link EncryptionService} (AES-256-GCM with a generated
 * key), real {@link ContextDataService} and {@link PolicyDataService} collaborators, and real
 * MongoDB round-trips — so that the create/find lifecycle, the email lookup query, password hashing
 * and verification, role changes, paging, counting, and deletion all run end to end rather than
 * against mocks.
 */
class UserServiceIT extends AbstractMongoIT {

    private UserService service;
    private ContextDataService contextDataService;
    private PolicyDataService policyDataService;
    private RedactListsDataService redactListsDataService;

    @BeforeEach
    void setUpServices() {
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);

        // A real encryption service backed by a freshly generated AES-256 key. The local key
        // provider returns the same key for every user, mirroring LocalEncryptionService.
        final EncryptionService encryptionService = new RealLocalEncryptionService();

        service = new UserService(mongoClient, encryptionService, audit);
        contextDataService = new ContextDataService(mongoClient, new ContextCache(null, 0, null, false), audit);
        policyDataService = new PolicyDataService(mongoClient, audit, new Gson());
        redactListsDataService = new RedactListsDataService(mongoClient, audit);
    }

    @Test
    void createUserPersistsAndIsReadableByEmailAndId() {
        final ServiceResponse response = service.createUser(
                "req", "alice@example.com", "s3cret", "user", policyDataService, contextDataService, "system");
        assertTrue(response.isSuccessful());

        // The email round-trips through the real encryption service and the real Mongo query path.
        final UserEntity byEmail = service.findByEmail("alice@example.com");
        assertNotNull(byEmail);
        assertEquals("alice@example.com", byEmail.getEmail());
        assertEquals("user", byEmail.getRole());

        final UserEntity byId = service.findOneById(byEmail.getId());
        assertNotNull(byId);
        assertEquals(byEmail.getId(), byId.getId());
        assertEquals("alice@example.com", byId.getEmail());

        // The stored password is hashed, not the plaintext.
        assertNotNull(byEmail.getPassword());
        assertFalse(byEmail.getPassword().equals("s3cret"));
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        assertTrue(service.createUser(
                "req", "dup@example.com", "pw", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ServiceResponse second = service.createUser(
                "req", "dup@example.com", "pw2", "user", policyDataService, contextDataService, "system");
        assertFalse(second.isSuccessful());
        assertEquals(1, service.count());
    }

    @Test
    void createUserWithPasswordChangeRequiredPersistsFlag() {
        assertTrue(service.createUser(
                "req", "admin@example.com", "admin", "admin", policyDataService, contextDataService, "system", true)
                .isSuccessful());

        final UserEntity user = service.findByEmail("admin@example.com");
        assertTrue(user.isPasswordChangeRequired());
    }

    @Test
    void createUserSeedsDefaultPolicyAndDefaultContext() {
        assertTrue(service.createUser(
                "req", "bob@example.com", "pw", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ObjectId userId = service.findByEmail("bob@example.com").getId();

        // A default policy is seeded for the new user...
        assertEquals(1, policyDataService.count(userId));
        // ...along with a default context owned by that user (names are unique per user).
        final ContextEntity defaultContext = contextDataService.findOne("default", userId);
        assertNotNull(defaultContext);
        assertEquals(userId, defaultContext.getUserId());
    }

    @Test
    void createUserAssignsAndPersistsAHexFpeKey() {
        assertTrue(service.createUser(
                "req", "fpe@example.com", "pw", "user", policyDataService, contextDataService, "system").isSuccessful());

        final UserEntity user = service.findByEmail("fpe@example.com");
        assertNotNull(user.getFpeKey());
        assertTrue(user.getFpeKey().matches("[0-9a-f]{64}"), "a new user must get a 256-bit hex FPE key");
    }

    @Test
    void ensureFpeKeyBackfillsAndPersistsForALegacyUserWithoutOne() {
        // Simulate a user created before per-user FPE keys existed by saving one with no key.
        final UserEntity legacy = new UserEntity();
        legacy.setEmail("legacy@example.com");
        legacy.setRole("user");
        final ObjectId id = service.save(legacy);
        assertNull(service.findOneById(id).getFpeKey());

        final String key = service.ensureFpeKey(service.findOneById(id));
        assertTrue(key.matches("[0-9a-f]{64}"));

        // The backfilled key is persisted and stable on subsequent calls.
        assertEquals(key, service.findOneById(id).getFpeKey());
        assertEquals(key, service.ensureFpeKey(service.findOneById(id)));
    }

    @Test
    void passwordMatchesReflectsStoredHash() {
        assertTrue(service.createUser(
                "req", "carol@example.com", "correct-horse", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByEmail("carol@example.com");
        assertTrue(service.passwordMatches(user, "correct-horse"));
        assertFalse(service.passwordMatches(user, "wrong"));
    }

    @Test
    void changePasswordUpdatesStoredHash() {
        assertTrue(service.createUser(
                "req", "dave@example.com", "old-password", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByEmail("dave@example.com");
        assertTrue(service.passwordMatches(user, "old-password"));

        assertTrue(service.changePassword("req", user, "new-password", "webui").isSuccessful());

        // Re-read from Mongo: the persisted hash now matches the new password, not the old one.
        final UserEntity reread = service.findByEmail("dave@example.com");
        assertTrue(service.passwordMatches(reread, "new-password"));
        assertFalse(service.passwordMatches(reread, "old-password"));
    }

    @Test
    void setUserRoleUpdatesPersistedRole() {
        assertTrue(service.createUser(
                "req", "erin@example.com", "pw", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByEmail("erin@example.com");
        assertTrue(service.setUserRole("req", user, "admin", "webui").isSuccessful());

        assertEquals("admin", service.findByEmail("erin@example.com").getRole());
    }

    @Test
    void deleteUserRemovesTheUserAndTheirContexts() {
        assertTrue(service.createUser(
                "req", "frank@example.com", "pw", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByEmail("frank@example.com");
        // The new user starts with an auto-created "default" context plus one we add here.
        assertTrue(contextDataService.create("extra", user.getId()).isSuccessful());
        assertEquals(2, contextDataService.findAll(user.getId()).size());

        service.deleteUser("req", user, contextDataService, "webui");

        assertNull(service.findByEmail("frank@example.com"));
        assertNull(service.findOneById(user.getId()));
        assertEquals(0, service.count());

        // The user's contexts are deleted along with the user.
        assertEquals(0, contextDataService.findAll(user.getId()).size());
        assertNull(contextDataService.findOne("default", user.getId()));
        assertNull(contextDataService.findOne("extra", user.getId()));
    }

    @Test
    void deleteUserRemovesTheirRedactLists() {
        assertTrue(service.createUser(
                "req", "grace@example.com", "pw", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByEmail("grace@example.com");

        // The user has global always-redact / never-redact terms (which can hold sensitive values).
        redactListsDataService.saveOrUpdate("req", user.getId(), List.of("ssn", "secret"), List.of("public"), "webui");
        assertNotNull(redactListsDataService.find(user.getId()));

        service.deleteUser("req", user, contextDataService, "webui");

        // The user's redact lists must not outlive them.
        assertNull(redactListsDataService.find(user.getId()));
    }

    @Test
    void findEmailsByIdsResolvesManyUsersInOneCall() {
        assertTrue(service.createUser("req", "h@example.com", "pw", "user", policyDataService, contextDataService, "system").isSuccessful());
        assertTrue(service.createUser("req", "i@example.com", "pw", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ObjectId h = service.findByEmail("h@example.com").getId();
        final ObjectId i = service.findByEmail("i@example.com").getId();
        final ObjectId missing = new ObjectId();

        final var emails = service.findEmailsByIds(List.of(h, i, missing));

        assertEquals("h@example.com", emails.get(h));
        assertEquals("i@example.com", emails.get(i));
        // An id with no matching user is simply absent, not an error.
        assertNull(emails.get(missing));
        assertEquals(2, emails.size());
    }

    @Test
    void findEmailsByIdsReturnsEmptyForEmptyInput() {
        assertTrue(service.findEmailsByIds(List.of()).isEmpty());
    }

    @Test
    void findAllSupportsPagingAndCount() {
        service.createUser("req", "u1@example.com", "pw", "user", policyDataService, contextDataService, "system");
        service.createUser("req", "u2@example.com", "pw", "user", policyDataService, contextDataService, "system");
        service.createUser("req", "u3@example.com", "pw", "user", policyDataService, contextDataService, "system");

        assertEquals(3, service.count());

        // Results are sorted ascending by email, so paging is deterministic.
        final List<UserEntity> firstPage = service.findAll(0, 2);
        assertEquals(2, firstPage.size());
        assertEquals("u1@example.com", firstPage.get(0).getEmail());
        assertEquals("u2@example.com", firstPage.get(1).getEmail());

        final List<UserEntity> secondPage = service.findAll(2, 2);
        assertEquals(1, secondPage.size());
        assertEquals("u3@example.com", secondPage.get(0).getEmail());
    }

    @Test
    void findByEmailAndFindOneByIdReturnNullWhenAbsent() {
        assertNull(service.findByEmail("missing@example.com"));
        assertNull(service.findOneById(new ObjectId()));
    }

    /**
     * A real, self-contained {@link EncryptionService} implementing AES-256-GCM with a single
     * generated key for all users. This avoids depending on the {@code PHILTER_ENCRYPTION_KEY}
     * environment variable while still exercising real cryptography through the encrypted-service
     * stack.
     */
    private static final class RealLocalEncryptionService extends EncryptionService {

        private static final String ALG = "AES/GCM/NoPadding";

        private RealLocalEncryptionService() {
            super(generatedKeyProvider());
        }

        private static KeyProvider generatedKeyProvider() {
            final String key = generateAes256Key();
            return new KeyProvider() {
                @Override
                public KeyResponse getKey(final String userId) {
                    return new KeyResponse(key, key);
                }
            };
        }

        private static String generateAes256Key() {
            try {
                final KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(256);
                final SecretKey secretKey = keyGen.generateKey();
                return Base64.getEncoder().encodeToString(secretKey.getEncoded());
            } catch (final Exception ex) {
                throw new RuntimeException("Unable to generate encryption key.", ex);
            }
        }

        @Override
        public String generateEncryptionKey() {
            return generateAes256Key();
        }

        @Override
        public EncryptResult encrypt(final String data, final String userId) {
            final KeyResponse keyResponse = keyProvider.getKey(userId);
            final byte[] keyBytes = EncryptionService.base64Decode(keyResponse.getPlainKey());
            try {
                final SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
                final byte[] ivBytes = new byte[16];
                new SecureRandom().nextBytes(ivBytes);
                final IvParameterSpec iv = new IvParameterSpec(ivBytes);
                final Cipher cipher = Cipher.getInstance(ALG, "BC");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);
                final byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
                final byte[] combined = new byte[ivBytes.length + encrypted.length];
                System.arraycopy(ivBytes, 0, combined, 0, ivBytes.length);
                System.arraycopy(encrypted, 0, combined, ivBytes.length, encrypted.length);
                return new EncryptResult(Base64.getEncoder().encodeToString(combined), keyResponse.getPlainKey());
            } catch (final Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public String decrypt(final String encryptedText, final String encryptionKey) {
            final byte[] keyBytes = EncryptionService.base64Decode(encryptionKey);
            final byte[] combined = Base64.getDecoder().decode(encryptedText);
            final byte[] ivBytes = new byte[16];
            System.arraycopy(combined, 0, ivBytes, 0, 16);
            final byte[] encrypted = new byte[combined.length - 16];
            System.arraycopy(combined, 16, encrypted, 0, encrypted.length);
            try {
                final SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
                final Cipher cipher = Cipher.getInstance(ALG, "BC");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBytes));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (final Exception ex) {
                throw new RuntimeException(ex);
            }
        }

    }

}
