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

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A standalone view that forces a signed-in user to set a new password. It is shown to users flagged
 * with {@code passwordChangeRequired} (for example, the seeded default admin) before any other
 * restricted view is reachable. It intentionally does not extend {@link AbstractRestrictedView} so
 * the forced-change redirect cannot loop back onto itself.
 */
@Route("change-password")
@PageTitle("Change Password | Philter")
@PermitAll
public class ChangePasswordView extends VerticalLayout implements BeforeEnterObserver {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserService userService;

    public ChangePasswordView(final UserService userService) {

        this.userService = userService;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        final PasswordField newPassword = new PasswordField("New Password");
        newPassword.setWidth("300px");
        newPassword.setRequired(true);

        final PasswordField confirmPassword = new PasswordField("Confirm New Password");
        confirmPassword.setWidth("300px");
        confirmPassword.setRequired(true);

        final Button submit = new Button("Change Password", event -> {

            final String password = newPassword.getValue();
            final String confirm = confirmPassword.getValue();

            if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                newPassword.setInvalid(true);
                newPassword.setErrorMessage("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
                return;
            }

            if (!password.equals(confirm)) {
                confirmPassword.setInvalid(true);
                confirmPassword.setErrorMessage("Passwords do not match.");
                return;
            }

            final UserEntity user = getCurrentUser();
            if (user == null) {
                UI.getCurrent().navigate(LoginView.class);
                return;
            }

            // Reject reusing the current password (covers the default 'admin' case).
            if (userService.passwordMatches(user, password)) {
                newPassword.setInvalid(true);
                newPassword.setErrorMessage("The new password must be different from the current password.");
                return;
            }

            final ServiceResponse response = userService.changePassword(
                    RequestIdGenerator.generate(), user, password, Source.WEBUI.getSource());

            if (response.isSuccessful()) {
                Notification.show("Password changed. Please continue.");
                UI.getCurrent().navigate(DashboardView.class);
            } else {
                Notification.show(response.getMessage());
            }

        });
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        add(new H2("Set a New Password"),
                new Paragraph("You must set a new password before continuing."),
                newPassword, confirmPassword, submit);

    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {

        final UserEntity user = getCurrentUser();

        if (user == null) {
            event.forwardTo(LoginView.class);
            return;
        }

        // If a change is not required, there is no reason to be here; go to the dashboard.
        if (!user.isPasswordChangeRequired()) {
            event.forwardTo(DashboardView.class);
        }

    }

    private UserEntity getCurrentUser() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return userService.findByEmail(authentication.getName());
    }

}
