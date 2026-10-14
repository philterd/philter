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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.mfa.TotpService;
import ai.philterd.philter.testutil.CountingEncryptionService;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link UserService}. */
public interface UserServiceContract extends StorageContract {

    private ServiceResponse create(final String username, final String role) {
        return storage().users().createUser("req", username, null, role, storage().policies(), storage().contexts(),
                "test");
    }

    private UserEntity createAndFind(final String username, final String role) {
        final ServiceResponse created = create(username, role);
        assertTrue(created.isSuccessful(), created.getMessage());
        return storage().users().findByUsername(username);
    }

    @Test
    default void userSignInAttemptsAreCountedInAWindowAndLockWithTheUser() {
        final String userId = createAndFind("jordan", UserService.ROLE_USER).getId();
        final long window = 60_000;
        assertEquals(1, storage().users().beginSignInAttempt(userId, 1_000, window));
        assertEquals(2, storage().users().beginSignInAttempt(userId, 30_000, window));
        // Another instance sees the same count: it is stored with the user.
        assertEquals(3, storage().anotherInstance().users().beginSignInAttempt(userId, 60_999, window));
        assertEquals(1, storage().users().beginSignInAttempt(userId, 61_000, window),
                "the window ran from the first attempt, so a new one starts");
        storage().users().resetSignInAttempts(userId);
        assertEquals(1, storage().users().beginSignInAttempt(userId, 61_001, window), "a reset starts again");

        assertFalse(storage().users().isSignInLocked(userId, 61_001));
        storage().users().lockSignIn(userId, 100_000);
        assertTrue(storage().anotherInstance().users().isSignInLocked(userId, 99_999));
        assertFalse(storage().users().isSignInLocked(userId, 100_000), "the lock ends on time");

        assertEquals(0, storage().users().beginSignInAttempt(RecordIds.newId(), 1_000, window), "no such user");
        assertFalse(storage().users().isSignInLocked(RecordIds.newId(), 1_000));
    }

    @Test
    default void userParallelSignInAttemptsAreAllCounted() throws Exception {
        final String userId = createAndFind("riley", UserService.ROLE_USER).getId();
        final List<Long> counts = StorageContract.concurrently(8, () ->
                storage().anotherInstance().users().beginSignInAttempt(userId, 1_000, 60_000));
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), new HashSet<>(counts),
                "each attempt gets its own number, so parallel attempts cannot all pass the limit");
    }

    @Test
    default void userNamesAreUniqueIncludingDeactivatedUsers() throws Exception {
        createAndFind("admin", UserService.ROLE_ADMIN);
        final List<ServiceResponse> results = StorageContract.concurrently(6,
                () -> storage().anotherInstance().users().createUser("req", "jordan", null, UserService.ROLE_USER,
                        storage().policies(), storage().contexts(), "test"));
        assertEquals(1, results.stream().filter(ServiceResponse::isSuccessful).count());
        final UserEntity jordan = storage().users().findByUsername("jordan");
        assertTrue(storage().users().deactivateUser("req", jordan, "test").isSuccessful());
        assertFalse(create("jordan", UserService.ROLE_USER).isSuccessful());
        assertEquals(2, storage().users().count(true));
    }

    @Test
    default void userConcurrentDemotionsAndDeactivationsLeaveAnActiveAdministrator() throws Exception {
        for (int round = 0; round < 5; round++) {
            final List<UserEntity> admins = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                admins.add(createAndFind("admin-" + round + "-" + i, UserService.ROLE_ADMIN));
            }
            // Administrators left from the previous round are demoted first, so only this round's can remain.
            for (final UserEntity user : storage().users().list(null, UserService.ROLE_ADMIN, true,
                    new Listings.Sort("username", false), 0, 100).items()) {
                if (admins.stream().noneMatch(admin -> admin.getId().equals(user.getId()))) {
                    assertTrue(storage().users().setUserRole("req", user, UserService.ROLE_USER, "test").isSuccessful());
                }
            }
            assertEquals(6, storage().users().countActiveAdmins());

            // Every administrator is removed at once, half demoted and half deactivated, through two instances.
            final AtomicInteger next = new AtomicInteger();
            final List<ServiceResponse> results = StorageContract.concurrently(6, () -> {
                final int i = next.getAndIncrement();
                final UserService users = i % 2 == 0 ? storage().users() : storage().anotherInstance().users();
                final UserEntity target = users.findOneById(admins.get(i).getId());
                return i < 3 ? users.setUserRole("req", target, UserService.ROLE_USER, "test")
                        : users.deactivateUser("req", target, "test");
            });

            assertEquals(1, storage().users().countActiveAdmins(), "exactly one active administrator remains");
            assertEquals(5, results.stream().filter(ServiceResponse::isSuccessful).count());
            final ServiceResponse refused = results.stream().filter(r -> !r.isSuccessful()).findFirst().orElseThrow();
            assertEquals(409, refused.getStatusCode());
        }
    }

    @Test
    default void userTheLastActiveAdministratorStays() {
        final UserEntity admin = createAndFind("admin", UserService.ROLE_ADMIN);
        assertFalse(storage().users().setUserRole("req", admin, UserService.ROLE_USER, "test").isSuccessful());
        assertFalse(storage().users().deactivateUser("req", admin, "test").isSuccessful());
        assertEquals(1, storage().users().countActiveAdmins());
        final UserEntity second = createAndFind("second", UserService.ROLE_ADMIN);
        assertTrue(storage().users().setUserRole("req", admin, UserService.ROLE_USER, "test").isSuccessful());
        assertFalse(storage().users().deactivateUser("req", storage().users().findOneById(second.getId()), "test")
                .isSuccessful());
    }

    @Test
    default void userAPasswordChangeNeedsThePasswordStillCurrent() {
        createAndFind("admin", UserService.ROLE_ADMIN);
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        assertTrue(storage().users().setPassword("req", user, "first-password-0123", false, "test", null, null)
                .isSuccessful());
        assertFalse(storage().users().changeOwnPassword("req", storage().users().findOneById(user.getId()),
                "wrong-password-01234", "second-password-0123", "test", null).isSuccessful());
        final UserEntity first = storage().users().findOneById(user.getId());
        final UserEntity stale = storage().anotherInstance().users().findOneById(user.getId());
        assertTrue(storage().users().changeOwnPassword("req", first, "first-password-0123", "second-password-0123",
                "test", null).isSuccessful());
        assertFalse(storage().anotherInstance().users().changeOwnPassword("req", stale, "first-password-0123",
                "third-password-01234", "test", null).isSuccessful());
        assertTrue(storage().users().passwordMatches(storage().users().findOneById(user.getId()), "second-password-0123"));
    }

    @Test
    default void userSecretsReadBackUnchanged() {
        createAndFind("admin", UserService.ROLE_ADMIN);
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        final String fpeKey = storage().users().ensureFpeKey(user);
        final String mfaSecret = storage().users().startMfaEnrollment(user);
        final UserEntity stored = storage().anotherInstance().users().findOneById(user.getId());
        assertEquals(fpeKey, stored.getFpeKey());
        assertEquals(fpeKey, storage().anotherInstance().users().ensureFpeKey(stored));
        assertEquals(mfaSecret, stored.getMfaPendingSecret());
    }

    @Test
    default void userListingsCountPageAndSearchAsPlainText() {
        createAndFind("admin", UserService.ROLE_ADMIN);
        for (final String username : List.of("a.b", "axb", "A.B-2", "other")) {
            createAndFind(username, UserService.ROLE_USER);
        }
        final Listings.Sort byUsername = new Listings.Sort("username", false);
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            final Listings.Page<UserEntity> page = storage().users().list(null, null, null, byUsername, offset, 2);
            assertEquals(5, page.total());
            page.items().forEach(user -> assertTrue(seen.add(user.getUsername())));
        }
        assertEquals(5, seen.size());
        final Listings.Page<UserEntity> found = storage().users().list("a.b", null, null, byUsername, 0, 10);
        assertEquals(Set.of("a.b", "A.B-2"), Set.copyOf(found.items().stream().map(UserEntity::getUsername).toList()));
        assertEquals(2, found.total());
    }

    /** Enrolls the user in MFA through the service, as the API does, and returns the secret. */
    private String enrollMfa(final UserEntity user) {
        final String secret = storage().users().startMfaEnrollment(user);
        assertTrue(storage().users().confirmMfaEnrollment("req", storage().users().findOneById(user.getId()),
                new TotpService().codeAt(secret, TotpService.currentTimeStep() - 1), "test", null).isSuccessful());
        return secret;
    }

    @Test
    default void userAnMfaCodeIsAcceptedOnceHoweverManyRequestsUseIt() throws Exception {
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        final String code = new TotpService().codeAt(enrollMfa(user), TotpService.currentTimeStep() + 1);
        final AtomicInteger next = new AtomicInteger();
        final List<UserService.MfaCheck> results = StorageContract.concurrently(6, () -> {
            final UserService users = next.getAndIncrement() % 2 == 0 ? storage().users() : storage().anotherInstance().users();
            return users.checkMfaCode("req", users.findOneById(user.getId()), code, "test");
        });
        assertEquals(1, results.stream().filter(result -> result == UserService.MfaCheck.ACCEPTED).count(), results.toString());
    }

    @Test
    default void userConcurrentBadMfaCodesAreAllCountedAndLockOnce() throws Exception {
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        enrollMfa(user);
        final AtomicInteger next = new AtomicInteger();
        final List<UserService.MfaCheck> results = StorageContract.concurrently(8, () -> {
            final UserService users = next.getAndIncrement() % 2 == 0 ? storage().users() : storage().anotherInstance().users();
            return users.checkMfaCode("req", users.findOneById(user.getId()), "000000", "test");
        });
        assertTrue(results.stream().noneMatch(result -> result == UserService.MfaCheck.ACCEPTED), results.toString());

        final UserEntity stored = storage().users().findOneById(user.getId());
        assertTrue(stored.isMfaLocked());
        assertTrue(stored.getMfaFailedAttempts() >= UserService.MAX_MFA_ATTEMPTS,
                "no failure was lost: " + stored.getMfaFailedAttempts());
        assertEquals(1, storage().auditLog().count(user.getId(), AuditLogEvent.USER_MFA_LOCKED.getAuditLogEvent(), null, null),
                "the lock is recorded once");
    }

    @Test
    default void userAnUnconfirmedMfaEnrollmentDoesNotApplyAndRemovalClearsEverything() {
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        final String pending = storage().users().startMfaEnrollment(user);
        final UserEntity stored = storage().anotherInstance().users().findOneById(user.getId());
        assertFalse(stored.isMfaEnabled());
        assertEquals(pending, stored.getMfaPendingSecret(), "the pending secret reads back unchanged");
        assertEquals(UserService.MfaCheck.REFUSED, storage().users().checkMfaCode("req", stored,
                new TotpService().codeAt(pending, TotpService.currentTimeStep()), "test"), "not enrolled yet");

        enrollMfa(stored);
        assertTrue(storage().users().removeMfa("req", storage().users().findOneById(user.getId()), "test", null, null)
                .isSuccessful());
        final UserEntity cleared = storage().anotherInstance().users().findOneById(user.getId());
        assertFalse(cleared.isMfaEnabled());
        assertNull(cleared.getMfaSecret());
        assertNull(cleared.getMfaPendingSecret());
        assertEquals(0, cleared.getMfaFailedAttempts());
        assertEquals(0L, cleared.getMfaLastUsedTimeStep());
    }

    @Test
    default void userCheckingWhetherAUserIsDeactivatedReadsNoSecret() {
        final UserEntity user = createAndFind("jordan", UserService.ROLE_USER);
        storage().users().startMfaEnrollment(user);
        // Every request made with an API key asks this, so it decrypts none of the user's secrets.
        final CountingEncryptionService encryption = new CountingEncryptionService(new TestEncryptionService());
        final UserService users = storage().usersDecryptingWith(encryption);
        assertFalse(users.isDeactivated(user.getId()));
        assertTrue(storage().users().deactivateUser("req", storage().users().findOneById(user.getId()), "test").isSuccessful());
        assertTrue(users.isDeactivated(user.getId()));
        assertTrue(users.isDeactivated(RecordIds.newId()), "a user that does not exist counts as deactivated");
        assertEquals(0, encryption.decryptions());
        users.findOneById(user.getId());
        assertTrue(encryption.decryptions() > 0, "reading the whole user does decrypt, so the count is not vacuous");
    }

}
