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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.BeforeEnterEvent;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The gate every dashboard page inherits. If {@code beforeEnter} stopped forwarding, every restricted
 * view would render for an unauthenticated visitor, and the forced password change would become
 * optional — and nothing verified either.
 */
class AbstractRestrictedViewTest {

    /**
     * A concrete view so the abstract base can be built. {@code getCurrentUser()} is overridden to
     * return whatever the test set, which is also what the base constructor asks for, so the drawer is
     * built for that user.
     */
    private static final class TestView extends AbstractRestrictedView {

        private static UserEntity currentUser;

        private TestView() {
            super(HeadlessDashboard.mongoClient(), mock(EncryptionService.class), mock(AuditEventPublisher.class));
        }

        @Override
        public UserEntity getCurrentUser() {
            return currentUser;
        }

    }

    private static UserEntity user(final String role, final boolean passwordChangeRequired) {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setEmail("someone@example.com");
        user.setRole(role);
        user.setPasswordChangeRequired(passwordChangeRequired);
        return user;
    }

    private static TestView viewFor(final UserEntity user) {
        TestView.currentUser = user;
        return new TestView();
    }

    @Test
    @DisplayName("A visitor with no user is sent to the login page and no further")
    void anUnauthenticatedVisitorIsForwardedToLogin() {

        try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("someone@example.com")) {

            final TestView view = viewFor(null);
            final BeforeEnterEvent event = mock(BeforeEnterEvent.class);

            view.beforeEnter(event);

            verify(event).forwardTo(LoginView.class);
            // Returning after the forward matters: the password check below dereferences the user.
            verify(event, never()).forwardTo(ChangePasswordView.class);

        }

    }

    @Test
    @DisplayName("A user who must change their password cannot reach anything else first")
    void aPasswordChangeIsForcedBeforeAnyOtherView() {

        try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("someone@example.com")) {

            final TestView view = viewFor(user("user", true));
            final BeforeEnterEvent event = mock(BeforeEnterEvent.class);

            view.beforeEnter(event);

            verify(event).forwardTo(ChangePasswordView.class);

        }

    }

    @Test
    @DisplayName("An ordinary signed-in user is not forwarded anywhere")
    void aSignedInUserPassesThrough() {

        try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("someone@example.com")) {

            final TestView view = viewFor(user("user", false));
            final BeforeEnterEvent event = mock(BeforeEnterEvent.class);

            view.beforeEnter(event);

            verify(event, never()).forwardTo(LoginView.class);
            verify(event, never()).forwardTo(ChangePasswordView.class);

        }

    }

    @Test
    @DisplayName("The role check is case-insensitive and refuses a missing user")
    void adminIsDecidedByTheRole() {

        try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("someone@example.com")) {

            assertTrue(viewFor(user("admin", false)).isAdmin());
            assertTrue(viewFor(user("ADMIN", false)).isAdmin(), "the stored role is not case-normalised");
            assertFalse(viewFor(user("user", false)).isAdmin());
            assertFalse(viewFor(null).isAdmin(), "no user is not an administrator");

        }

    }

    @Test
    @DisplayName("The Administration menu is built only for an administrator")
    void theAdminMenuIsNotOfferedToAnOrdinaryUser() {

        try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("someone@example.com")) {

            assertTrue(navigationLabels(viewFor(user("admin", false))).contains("Admin"),
                    "an administrator must be offered the Admin page");

            final List<String> ordinary = navigationLabels(viewFor(user("user", false)));
            assertFalse(ordinary.contains("Admin"),
                    "an ordinary user must not be offered the Admin page; saw " + ordinary);

            // The rest of the dashboard is still there, so the assertion above is about the Admin item
            // and not about an empty drawer.
            assertTrue(ordinary.contains("Dashboard") && ordinary.contains("My Account"),
                    "the ordinary user's own pages must still be listed; saw " + ordinary);

        }

    }

    /** Every navigation label in the drawer, whatever it is nested under. */
    private static List<String> navigationLabels(final Component root) {

        final List<String> labels = new ArrayList<>();

        root.getChildren().forEach(child -> collect(child, labels));

        return labels;

    }

    private static void collect(final Component component, final List<String> labels) {

        if (component instanceof SideNavItem item) {
            labels.add(item.getLabel());
        }

        component.getChildren().forEach(child -> collect(child, labels));

    }

}
