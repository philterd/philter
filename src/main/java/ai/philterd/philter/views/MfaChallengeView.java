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
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.mfa.TotpService;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * The second step of an MFA-enrolled user's login: after the username and password are accepted by the
 * standard login form, {@code MfaNavigationGuard} reroutes the user here until they enter a valid TOTP
 * code. This is intentionally a bare page (no dashboard navigation): the user is authenticated but not
 * yet allowed past the MFA gate, so nothing else should be reachable.
 */
@Route("mfa")
@PageTitle("Verify | Philter")
@PermitAll
public class MfaChallengeView extends VerticalLayout implements BeforeEnterObserver {

    /**
     * Vaadin session attribute set once the MFA requirement is satisfied for the session, either because
     * the user verified a code here or because the user is not enrolled. The navigation guard reads it to
     * avoid gating (and re-querying the user) on every navigation.
     */
    public static final String MFA_SATISFIED_ATTRIBUTE = "philter.mfaSatisfied";

    private final UserService userService;
    private final TotpService totpService;

    public MfaChallengeView(final UserService userService, final TotpService totpService) {

        this.userService = userService;
        this.totpService = totpService;

        addClassName("login-view");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        final TextField codeField = new TextField("Authentication code");
        codeField.setPlaceholder("123456");
        codeField.setWidth("240px");

        final Button verifyButton = new Button("Verify", e -> verify(codeField));
        verifyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        // Submitting the code with Enter is the natural interaction for a single-field prompt.
        codeField.addKeyDownListener(Key.ENTER, e -> verify(codeField));

        final Button signOutButton = new Button("Sign out", e -> {
            new SecurityContextLogoutHandler().logout(
                    VaadinServletRequest.getCurrent().getHttpServletRequest(), null, null);
            UI.getCurrent().navigate(LoginView.class);
        });
        signOutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        add(new H1("Philter"),
                new Span("Enter the code from your authenticator app to finish signing in."),
                codeField, verifyButton, signOutButton);

    }

    private void verify(final TextField codeField) {

        final UserEntity user = currentUser();

        // Defensive: if the account no longer requires MFA, treat the gate as satisfied and continue.
        if (user == null || !user.isMfaEnabled()) {
            markSatisfiedAndContinue();
            return;
        }

        // A locked account cannot finish signing in until an administrator unlocks it.
        if (user.isMfaLocked()) {
            codeField.setInvalid(true);
            codeField.setErrorMessage("Your account is locked after too many failed attempts. Ask an administrator to unlock it.");
            return;
        }

        final String code = codeField.getValue();
        if (code == null || code.isBlank() || !totpService.verifyCode(user.getMfaSecret(), code)) {
            final boolean nowLocked = userService.recordFailedMfaAttempt(
                    RequestIdGenerator.generate(), user, Source.WEBUI.getSource());
            codeField.setInvalid(true);
            if (nowLocked) {
                codeField.setErrorMessage("Too many failed attempts. Your account is locked; ask an administrator to unlock it.");
            } else {
                codeField.setErrorMessage("That code is not valid. Check your authenticator app and try again.");
            }
            return;
        }

        userService.resetMfaAttempts(user);
        markSatisfiedAndContinue();
    }

    private void markSatisfiedAndContinue() {
        VaadinSession.getCurrent().setAttribute(MFA_SATISFIED_ATTRIBUTE, Boolean.TRUE);
        UI.getCurrent().navigate("dashboard");
    }

    private UserEntity currentUser() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return userService.findByUsername(authentication.getName());
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        // If the user lands here but does not actually need to verify (already satisfied this session, or
        // not enrolled), send them on to the dashboard rather than showing an unnecessary prompt.
        final VaadinSession session = VaadinSession.getCurrent();
        if (session != null && Boolean.TRUE.equals(session.getAttribute(MFA_SATISFIED_ATTRIBUTE))) {
            event.forwardTo("dashboard");
            return;
        }
        final UserEntity user = currentUser();
        if (user == null || !user.isMfaEnabled()) {
            if (session != null) {
                session.setAttribute(MFA_SATISFIED_ATTRIBUTE, Boolean.TRUE);
            }
            event.forwardTo("dashboard");
        }
    }

}
