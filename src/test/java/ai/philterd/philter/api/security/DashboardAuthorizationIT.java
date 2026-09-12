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
package ai.philterd.philter.api.security;

import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The recheck that stands between an administrator's earlier authentication and a settings or
 * signing mutation. The account checks hold for every channel; the session checks are for a
 * dashboard request, which is the only channel that has a session to check.
 */
class DashboardAuthorizationIT extends AbstractMongoIT {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Stands in for an API request: authenticated, but carrying no dashboard principal. */
    private void authenticateWithoutADashboardSession() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("api", "n/a", List.of()));
    }

    private ObjectId user(final String role, final boolean deactivated, final boolean mfaLocked) {
        final ObjectId id = new ObjectId();
        mongoClient.getDatabase("philter").getCollection("users").insertOne(new Document("_id", id)
                .append("role", role)
                .append("security_version", 1L)
                .append("deactivated", deactivated)
                .append("mfa_locked", mfaLocked)
                .append("mfa_enabled", false));
        return id;
    }

    private void require(final ObjectId actingUserId) {
        DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);
    }

    @Test
    @DisplayName("With no request context at all the recheck does not apply")
    void noAuthenticationIsStartupNotARequest() {
        // Startup and background work have no caller to recheck, and must not be blocked by one.
        assertDoesNotThrow(() -> require(null));
        assertDoesNotThrow(() -> require(user("admin", false, false)));
    }

    @Test
    @DisplayName("An administrator acting without a dashboard session is allowed")
    void anApiCallerThatIsAnAdministratorIsAllowed() {
        final ObjectId admin = user("admin", false, false);
        authenticateWithoutADashboardSession();
        assertDoesNotThrow(() -> require(admin));
    }

    @Test
    @DisplayName("The account checks hold whether or not there is a dashboard session")
    void theAccountMustStillBeAnActiveAdministrator() {
        authenticateWithoutADashboardSession();

        assertThrows(AccessDeniedException.class, () -> require(user("user", false, false)),
                "a non-administrator must be refused");
        assertThrows(AccessDeniedException.class, () -> require(user("admin", true, false)),
                "a deactivated administrator must be refused");
        assertThrows(AccessDeniedException.class, () -> require(user("admin", false, true)),
                "an MFA-locked administrator must be refused");
    }

    @Test
    @DisplayName("A caller that is present but not authenticated is refused")
    void anUnauthenticatedCallerIsRefused() {
        final ObjectId admin = user("admin", false, false);

        // The two-argument token is deliberately not authenticated. An admin id must not carry it.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("api", "n/a"));

        assertThrows(AccessDeniedException.class, () -> require(admin));
    }

    @Test
    @DisplayName("An unknown or unnamed account is refused")
    void anAccountThatCannotBeResolvedIsRefused() {
        authenticateWithoutADashboardSession();

        assertThrows(AccessDeniedException.class, () -> require(null),
                "an action with no acting user must be refused");
        assertThrows(AccessDeniedException.class, () -> require(new ObjectId()),
                "an account that no longer exists must be refused");
    }

}
