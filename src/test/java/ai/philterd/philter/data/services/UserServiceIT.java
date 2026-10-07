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
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.KeyProvider;
import ai.philterd.philter.services.encryption.KeyResponse;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.utils.PathSafeNames;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import ai.philterd.philter.services.signing.SigningService;
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

import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import ai.philterd.philter.model.AuditLogEvent;
import java.util.Collections;

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
        policyDataService = new PolicyDataService(mongoClient, audit, new Gson(),
                new PolicyVersionDataService(mongoClient, audit), new ai.philterd.philter.services.cache.RedactionCache());
        redactListsDataService = new RedactListsDataService(mongoClient, encryptionService, audit);
    }

    /** An enrolled user and the secret, enrolled through the service as the API does. */
    private String enrollMfa(final UserService users, final UserEntity user) {
        final String secret = users.startMfaEnrollment(user);
        final ai.philterd.philter.services.mfa.TotpService totp = new ai.philterd.philter.services.mfa.TotpService();
        assertTrue(users.confirmMfaEnrollment("req", user,
                totp.codeAt(secret, ai.philterd.philter.services.mfa.TotpService.currentTimeStep() - 1), "test", null).isSuccessful());
        return secret;
    }

    @Test
    void twoRequestsRacingWithOneCodeAreAcceptedOnce() throws Exception {
        final UserEntity user = createAndFind("mfa-race", "user");
        final String secret = enrollMfa(service, user);
        final String code = new ai.philterd.philter.services.mfa.TotpService()
                .codeAt(secret, ai.philterd.philter.services.mfa.TotpService.currentTimeStep() + 1);

        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            final var a = executor.submit(() -> { start.await(); return service.checkMfaCode("req", service.findOneById(user.getId()), code, "test"); });
            final var b = executor.submit(() -> { start.await(); return service.checkMfaCode("req", service.findOneById(user.getId()), code, "test"); });
            start.countDown();
            final java.util.List<UserService.MfaCheck> results = java.util.List.of(a.get(), b.get());
            assertEquals(1, results.stream().filter(r -> r == UserService.MfaCheck.ACCEPTED).count(),
                    "a code works once, however the requests interleave: " + results);
        }
    }

    @Test
    void concurrentBadCodesAreAllCountedAndLockOnce() throws Exception {
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        final UserService users = new UserService(mongoClient, new RealLocalEncryptionService(), audit);
        final UserEntity user = createAndFind("mfa-burst", "user");
        enrollMfa(users, user);

        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            final java.util.List<java.util.concurrent.Future<UserService.MfaCheck>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(executor.submit(() -> { start.await(); return users.checkMfaCode("req", users.findOneById(user.getId()), "000000", "test"); }));
            }
            start.countDown();
            for (final var attempt : attempts) {
                assertTrue(attempt.get() != UserService.MfaCheck.ACCEPTED);
            }
        }

        final UserEntity stored = users.findOneById(user.getId());
        assertTrue(stored.isMfaLocked());
        assertTrue(stored.getMfaFailedAttempts() >= UserService.MAX_MFA_ATTEMPTS, "no failure was lost: " + stored.getMfaFailedAttempts());
        verify(audit, times(1)).auditEvent(any(), eq(AuditLogEvent.USER_MFA_LOCKED), eq(user.getId()), eq(user.getId()), any(), any());
    }

    @Test
    void anUnconfirmedEnrollmentDoesNotApplyAndRemovalClearsEverything() {
        final UserEntity user = createAndFind("mfa-pending", "user");
        final String pending = service.startMfaEnrollment(user);
        final UserEntity stored = service.findOneById(user.getId());
        assertFalse(stored.isMfaEnabled());
        assertEquals(pending, stored.getMfaPendingSecret(), "the pending secret round-trips through encryption");
        assertEquals(UserService.MfaCheck.REFUSED, service.checkMfaCode("req", stored,
                new ai.philterd.philter.services.mfa.TotpService().codeAt(pending,
                        ai.philterd.philter.services.mfa.TotpService.currentTimeStep()), "test"), "not enrolled yet");

        enrollMfa(service, stored);
        assertTrue(service.removeMfa("req", service.findOneById(user.getId()), "test", null, null).isSuccessful());
        final UserEntity cleared = service.findOneById(user.getId());
        assertFalse(cleared.isMfaEnabled());
        assertNull(cleared.getMfaSecret());
        assertNull(cleared.getMfaPendingSecret());
        assertEquals(0, cleared.getMfaFailedAttempts());
        assertEquals(0L, cleared.getMfaLastUsedTimeStep());
    }

    @Test
    void passwordRulesCountCharactersAndUtf8Bytes() {
        assertNotNull(UserService.passwordProblem(null));
        assertNotNull(UserService.passwordProblem("x".repeat(15)));
        assertNull(UserService.passwordProblem("x".repeat(16)));
        assertNull(UserService.passwordProblem("x".repeat(72)));
        assertNotNull(UserService.passwordProblem("x".repeat(73)));
        // 16 characters of two code units each count as 16, not 32.
        assertNull(UserService.passwordProblem("\uD83D\uDE00".repeat(16)));
        // 24 three-byte characters are 72 bytes; 25 are 75.
        assertNull(UserService.passwordProblem("\u20ac".repeat(24)));
        assertNotNull(UserService.passwordProblem("\u20ac".repeat(25)));

        // bcrypt accepts the longest allowed password, and every byte of it counts.
        final UserEntity user = createAndFind("longest-password", "user");
        final String longest = "x".repeat(71) + "a";
        assertTrue(service.setPassword("req", user, longest, false, "test", null, null).isSuccessful());
        assertTrue(service.passwordMatches(service.findOneById(user.getId()), longest));
        assertFalse(service.passwordMatches(service.findOneById(user.getId()), "x".repeat(71) + "b"),
                "the 72nd byte is part of the password");
    }

    @Test
    void aStaleCopyCannotChangeAPasswordSomeoneElseAlreadyChanged() {
        final UserEntity user = createAndFind("stale-password", "user");
        assertTrue(service.setPassword("req", user, "first-password-0123", false, "test", null, null).isSuccessful());

        final UserEntity first = service.findOneById(user.getId());
        final UserEntity second = service.findOneById(user.getId());
        assertTrue(service.changeOwnPassword("req", first, "first-password-0123", "second-password-0123", "test", null)
                .isSuccessful());

        // The second request read the old hash, so its current password checks out, but the write must not land.
        final ServiceResponse late = service.changeOwnPassword("req", second, "first-password-0123",
                "third-password-01234", "test", null);
        assertFalse(late.isSuccessful());
        assertEquals(409, late.getStatusCode());
        assertTrue(service.passwordMatches(service.findOneById(user.getId()), "second-password-0123"));
    }

    @Test
    void createUserPersistsAndIsReadableByEmailAndId() {
        final ServiceResponse response = service.createUser(
                "req", "alice@example.com", "user", policyDataService, contextDataService, "system");
        assertTrue(response.isSuccessful());

        // The username round-trips through the real Mongo query path.
        final UserEntity byEmail = service.findByUsername("alice@example.com");
        assertNotNull(byEmail);
        assertEquals("alice@example.com", byEmail.getUsername());
        assertEquals("user", byEmail.getRole());

        final UserEntity byId = service.findOneById(byEmail.getId());
        assertNotNull(byId);
        assertEquals(byEmail.getId(), byId.getId());
        assertEquals("alice@example.com", byId.getUsername());
    }

    @Test
    void createUserRefusesAUsernameThatCannotBeUsedInAPath() {
        final IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.createUser(
                        "req", "ops/ci", "user", policyDataService, contextDataService, "system"));
        assertTrue(refused.getMessage().contains(PathSafeNames.RULE), refused.getMessage());
        assertEquals(0, service.count());
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        assertTrue(service.createUser(
                "req", "dup@example.com", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ServiceResponse second = service.createUser(
                "req", "dup@example.com", "user", policyDataService, contextDataService, "system");
        assertFalse(second.isSuccessful());
        assertEquals(1, service.count());
    }

    @Test
    void createUserSeedsDefaultPolicyAndDefaultContext() {
        assertTrue(service.createUser(
                "req", "bob@example.com", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ObjectId userId = service.findByUsername("bob@example.com").getId();

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
                "req", "fpe@example.com", "user", policyDataService, contextDataService, "system").isSuccessful());

        final UserEntity user = service.findByUsername("fpe@example.com");
        assertNotNull(user.getFpeKey());
        assertTrue(user.getFpeKey().matches("[0-9a-f]{64}"), "a new user must get a 256-bit hex FPE key");
    }

    @Test
    void missingFpeKeyFailsWithoutChangingAccount() {
        final UserEntity user = new UserEntity();
        user.setUsername("missing-key"); user.setRole("user");
        final ObjectId id = service.save(user);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.ensureFpeKey(service.findOneById(id)));
        assertNull(service.findOneById(id).getFpeKey());
    }

    @Test
    void setUserRoleUpdatesPersistedRole() {
        assertTrue(service.createUser(
                "req", "erin@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByUsername("erin@example.com");
        assertTrue(service.setUserRole("req", user, "admin", "system").isSuccessful());

        assertEquals("admin", service.findByUsername("erin@example.com").getRole());
    }

    @Test
    void deactivateUserRetainsTheUserAndAllOfTheirData() {
        assertTrue(service.createUser(
                "req", "frank@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByUsername("frank@example.com");
        // The new user starts with an auto-created "default" context plus one we add here.
        assertTrue(contextDataService.create("extra", user.getId()).isSuccessful());
        assertEquals(2, contextDataService.findAll(user.getId()).size());

        service.deactivateUser("req", user, "system");

        // A deactivated user cannot sign in or be looked up by email...
        assertNull(service.findByUsername("frank@example.com"));
        // ...but the record is retained, marked deactivated, so audit and ledger references resolve.
        final UserEntity retained = service.findOneById(user.getId());
        assertNotNull(retained);
        assertTrue(retained.isDeactivated());
        assertNotNull(retained.getDeactivatedAt());
        assertEquals("frank@example.com", retained.getUsername());

        // The retained user counts toward the all-users total but not the active-users total.
        assertEquals(1, service.count());
        assertEquals(0, service.count(false));

        // Deactivation retains all of the user's data: contexts are untouched.
        assertEquals(2, contextDataService.findAll(user.getId()).size());
        assertNotNull(contextDataService.findOne("default", user.getId()));
        assertNotNull(contextDataService.findOne("extra", user.getId()));
    }

    @Test
    void deactivateUserRetainsPoliciesAndLedgerResolvableToTheUser() throws Exception {
        assertTrue(service.createUser(
                "req", "evidence@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());
        final UserEntity user = service.findByUsername("evidence@example.com");
        final ObjectId userId = user.getId();

        // The user owns a policy (the default seeded at creation) ...
        assertTrue(policyDataService.count(userId) >= 1);

        // ... and a redaction-ledger chain (governance evidence).
        final LegalHoldDataService noOpHoldService = mock(LegalHoldDataService.class);
        when(noOpHoldService.hasAnyHold(any())).thenReturn(false);
        when(noOpHoldService.isProtectedDocument(any(), any())).thenReturn(false);
        when(noOpHoldService.findAllHoldsForUser(any())).thenReturn(java.util.Collections.emptyList());
        when(noOpHoldService.findBlockingHoldsForDocument(any(), any())).thenReturn(java.util.Collections.emptyList());
        final SigningService ledgerSigner = mock(SigningService.class);
        when(ledgerSigner.signLedgerEntry(any())).thenReturn(new SigningService.LedgerSignature("signature", "key"));
        final LedgerDataService ledgerDataService = new LedgerDataService(
                mongoClient, new RealLocalEncryptionService(), mock(AuditEventPublisher.class),
                noOpHoldService, ledgerSigner);
        ledgerDataService.initializeLedger(userId, "doc-1", "input-hash", "file.txt", "default", 0, "policy-hash");
        assertEquals(1, ledgerDataService.countChainsByUserId(userId));

        service.deactivateUser("req", user, "system");

        // Deactivation must not cascade: the policies and the ledger are retained ...
        assertTrue(policyDataService.count(userId) >= 1, "policies must survive user deactivation");
        assertEquals(1, ledgerDataService.countChainsByUserId(userId), "the redaction ledger must survive user deactivation");

        // ... and remain resolvable to the retained (deactivated) owning user.
        final UserEntity retained = service.findOneById(userId);
        assertNotNull(retained);
        assertTrue(retained.isDeactivated());
        assertEquals("evidence@example.com", retained.getUsername());
    }

    @Test
    void reactivateUserRestoresAccessAndData() {
        assertTrue(service.createUser(
                "req", "henry@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());
        final UserEntity user = service.findByUsername("henry@example.com");
        assertTrue(contextDataService.create("extra", user.getId()).isSuccessful());

        service.deactivateUser("req", user, "system");
        assertNull(service.findByUsername("henry@example.com"));

        service.reactivateUser("req", user, "system");

        // Sign-in resolves again and the account is active.
        final UserEntity reactivated = service.findByUsername("henry@example.com");
        assertNotNull(reactivated);
        assertFalse(reactivated.isDeactivated());
        assertNull(reactivated.getDeactivatedAt());

        // The data was never removed, so it is all still there.
        assertEquals(2, contextDataService.findAll(user.getId()).size());
        assertEquals(1, service.count(false));
    }

    @Test
    void deactivatedEmailStaysReservedAndIsExcludedFromActiveListing() {
        assertTrue(service.createUser(
                "req", "reuse@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());
        final UserEntity first = service.findByUsername("reuse@example.com");

        service.deactivateUser("req", first, "system");

        // The email stays reserved by the deactivated account: a duplicate cannot be created.
        assertFalse(service.createUser(
                "req", "reuse@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        // Only one row exists; it is excluded from the active listing but present in the full one.
        assertEquals(1, service.count());
        assertEquals(0, service.count(false));
        assertEquals(1, service.findAll(0, 100, true).size());
        assertEquals(0, service.findAll(0, 100, false).size());
    }

    @Test
    void deactivateUserRetainsTheirRedactLists() {
        assertTrue(service.createUser(
                "req", "grace@example.com", "user", policyDataService, contextDataService, "system")
                .isSuccessful());

        final UserEntity user = service.findByUsername("grace@example.com");

        // The user has global always-redact / never-redact terms (which can hold sensitive values).
        redactListsDataService.saveOrUpdate("req", user.getId(), List.of("ssn", "secret"), List.of("public"), "system");
        assertNotNull(redactListsDataService.find(user.getId()));

        service.deactivateUser("req", user, "system");

        // Deactivation retains the user's data, including their redact lists, so they are restored on
        // reactivation.
        assertNotNull(redactListsDataService.find(user.getId()));
    }

    @Test
    void findUsernamesByIdsResolvesManyUsersInOneCall() {
        assertTrue(service.createUser("req", "h@example.com", "user", policyDataService, contextDataService, "system").isSuccessful());
        assertTrue(service.createUser("req", "i@example.com", "user", policyDataService, contextDataService, "system").isSuccessful());

        final ObjectId h = service.findByUsername("h@example.com").getId();
        final ObjectId i = service.findByUsername("i@example.com").getId();
        final ObjectId missing = new ObjectId();

        final var emails = service.findUsernamesByIds(List.of(h, i, missing));

        assertEquals("h@example.com", emails.get(h));
        assertEquals("i@example.com", emails.get(i));
        // An id with no matching user is simply absent, not an error.
        assertNull(emails.get(missing));
        assertEquals(2, emails.size());
    }

    @Test
    void findUsernamesByIdsReturnsEmptyForEmptyInput() {
        assertTrue(service.findUsernamesByIds(List.of()).isEmpty());
    }

    @Test
    void findAllSupportsPagingAndCount() {
        service.createUser("req", "u1@example.com", "user", policyDataService, contextDataService, "system");
        service.createUser("req", "u2@example.com", "user", policyDataService, contextDataService, "system");
        service.createUser("req", "u3@example.com", "user", policyDataService, contextDataService, "system");

        assertEquals(3, service.count());

        // Results are sorted ascending by username, so paging is deterministic.
        final List<UserEntity> firstPage = service.findAll(0, 2);
        assertEquals(2, firstPage.size());
        assertEquals("u1@example.com", firstPage.get(0).getUsername());
        assertEquals("u2@example.com", firstPage.get(1).getUsername());

        final List<UserEntity> secondPage = service.findAll(2, 2);
        assertEquals(1, secondPage.size());
        assertEquals("u3@example.com", secondPage.get(0).getUsername());
    }

    @Test
    void findByUsernameAndFindOneByIdReturnNullWhenAbsent() {
        assertNull(service.findByUsername("missing@example.com"));
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

                // This double does not wrap, so the stored key is already the data key.
                @Override
                public String decryptKey(final String storedKey) {
                    return storedKey;
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

    
    @Override
    public ai.philterd.philter.services.encryption.EncryptedBytes encryptBytes(final byte[] data, final String userId) {
        final ai.philterd.philter.services.encryption.EncryptResult result =
                encrypt(java.util.Base64.getEncoder().encodeToString(data), userId);
        return new ai.philterd.philter.services.encryption.EncryptedBytes(
                result.getEncryptedText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                result.getEncryptionKey());
    }

    @Override
    public byte[] decryptBytes(final byte[] encrypted, final String encryptionKey) {
        return java.util.Base64.getDecoder().decode(
                decrypt(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8), encryptionKey));
    }
}


    private UserEntity createAndFind(final String username, final String role) {
        assertTrue(service.createUser("req", username, role, policyDataService, contextDataService, "system").isSuccessful());
        return service.findByUsername(username);
    }

    @Test
    void theLastActiveAdministratorCannotBeDemotedOrDeactivated() {
        final UserEntity admin = createAndFind("only-admin", "admin");
        createAndFind("someone", "user");

        assertTrue(service.isLastActiveAdmin(admin));
        assertFalse(service.setUserRole("req", admin, "user", "api", null, null).isSuccessful());
        assertFalse(service.deactivateUser("req", admin, "api", null, null).isSuccessful());

        final UserEntity reloaded = service.findOneById(admin.getId());
        assertEquals("admin", reloaded.getRole());
        assertFalse(reloaded.isDeactivated());
    }

    @Test
    void anAdministratorCanBeRemovedWhileAnotherRemainsActive() {
        final UserEntity first = createAndFind("admin-one", "admin");
        final UserEntity second = createAndFind("admin-two", "admin");

        assertTrue(service.deactivateUser("req", second, "api", null, null).isSuccessful());
        assertEquals(1, service.countActiveAdmins(), "a deactivated administrator is not counted");

        // Now the first is the only active administrator left.
        assertFalse(service.setUserRole("req", service.findOneById(first.getId()), "user", "api", null, null).isSuccessful());

        assertTrue(service.reactivateUser("req", service.findOneById(second.getId()), "api", null, null).isSuccessful());
        assertTrue(service.setUserRole("req", service.findOneById(first.getId()), "user", "api", null, null).isSuccessful());
    }

    @Test
    void deactivatingTwiceOrReactivatingAnActiveUserFails() {
        final UserEntity user = createAndFind("twice", "user");

        assertFalse(service.reactivateUser("req", user, "api", null, null).isSuccessful());
        assertTrue(service.deactivateUser("req", service.findOneById(user.getId()), "api", null, null).isSuccessful());
        assertFalse(service.deactivateUser("req", service.findOneById(user.getId()), "api", null, null).isSuccessful());
    }

    @Test
    void changesAreAuditedWithTheActingPrincipal() {
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        service = new UserService(mongoClient, new RealLocalEncryptionService(), audit);
        createAndFind("other-admin", "admin");
        final UserEntity user = createAndFind("audited", "user");
        final ObjectId acting = new ObjectId();
        final ObjectId actingKey = new ObjectId();

        service.setUserRole("req", user, "admin", "api", acting, actingKey);
        service.deactivateUser("req", service.findOneById(user.getId()), "api", acting, actingKey);
        service.reactivateUser("req", service.findOneById(user.getId()), "api", acting, actingKey);

        // The principal is the acting user and the details name the key it used.
        verify(audit).auditEvent(eq("req"), eq(AuditLogEvent.USER_ROLE_CHANGED), eq(acting), eq(user.getId()), eq("api"),
                eq("role: admin, api_key: " + actingKey));
        verify(audit).auditEvent(eq("req"), eq(AuditLogEvent.USER_DEACTIVATED), eq(acting), eq(user.getId()), eq("api"),
                endsWith(", api_key: " + actingKey));
        verify(audit).auditEvent(eq("req"), eq(AuditLogEvent.USER_REACTIVATED), eq(acting), eq(user.getId()), eq("api"),
                eq("api_key: " + actingKey));
    }

    @Test
    void setWebhookValidatesBeforeSavingAndAuditsTheActor() {
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        service = new UserService(mongoClient, new RealLocalEncryptionService(), audit);
        final UserEntity user = createAndFind("hooked", "user");
        final ObjectId acting = new ObjectId();
        final ObjectId actingKey = new ObjectId();

        // Refused: nothing is stored and nothing is audited.
        assertFalse(service.setWebhook("req", user, "https://127.0.0.1/hook", "a-secret-of-16ch", null, "api", acting, actingKey).isSuccessful());
        assertNull(service.findOneById(user.getId()).getWebhookUrl());
        verify(audit, times(0)).auditEvent(any(), eq(AuditLogEvent.WEBHOOK_CONFIGURED), any(), any(), any(), any());

        assertTrue(service.setWebhook("req", user, " https://93.184.216.34/hook ", "a-secret-of-16ch", null, "api", acting, actingKey).isSuccessful());
        final UserEntity saved = service.findOneById(user.getId());
        assertEquals("https://93.184.216.34/hook", saved.getWebhookUrl());
        assertEquals("a-secret-of-16ch", saved.getWebhookSecret());
        final org.bson.Document stored = mongoClient.getDatabase("philter").getCollection("users")
                .find(new org.bson.Document("_id", user.getId())).first();
        assertFalse(String.valueOf(stored.get("webhook_secret")).contains("a-secret-of-16ch"), "the secret is encrypted at rest");
        verify(audit).auditEvent(eq("req"), eq(AuditLogEvent.WEBHOOK_CONFIGURED), eq(acting), eq(user.getId()), eq("api"),
                eq("api_key: " + actingKey));

        assertTrue(service.removeWebhook("req", saved, "api", acting, actingKey).isSuccessful());
        final UserEntity removed = service.findOneById(user.getId());
        assertNull(removed.getWebhookUrl());
        assertNull(removed.getWebhookSecret());
        verify(audit).auditEvent(eq("req"), eq(AuditLogEvent.WEBHOOK_REMOVED), eq(acting), eq(user.getId()), eq("api"),
                eq("api_key: " + actingKey));
    }

}
