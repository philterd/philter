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
package ai.philterd.philter.config;

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.views.MfaChallengeView;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces multi-factor authentication as a mandatory post-login gate. After the standard username and
 * password login succeeds, this guard reroutes an MFA-enrolled user to {@link MfaChallengeView} on every
 * navigation until they verify a TOTP code (which sets {@link MfaChallengeView#MFA_SATISFIED_ATTRIBUTE}
 * on the Vaadin session). Users who are not enrolled are never gated.
 *
 * <p>This runs at the Vaadin routing layer rather than in Spring Security, so the existing form login and
 * its CSRF handling are untouched. The request filter also checks the account security version on component RPCs, so
 * a newly enrolled account cannot keep using components from an older session. The API uses API keys.
 */
@Component
public class MfaNavigationGuard implements VaadinServiceInitListener {

    private final UserService userService;

    public MfaNavigationGuard(final UserService userService) {
        this.userService = userService;
    }

    @Override
    public void serviceInit(final ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit ->
                uiInit.getUI().addBeforeEnterListener(this::beforeEnter));
    }

    private void beforeEnter(final BeforeEnterEvent event) {

        final VaadinSession session = VaadinSession.getCurrent();

        // Always let the challenge view itself through so the user can enter a code.
        if (event.getNavigationTarget() == MfaChallengeView.class) {
            return;
        }

        // Only authenticated, non-anonymous users can be subject to MFA.
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return;
        }

        final UserEntity user = userService.findByUsername(auth.getName());

        if (user != null && (!user.isMfaEnabled() || MfaChallengeView.isSatisfied(session, user))) {
            return;
        }

        // Enrolled but not yet verified this session: force the challenge.
        event.rerouteTo(MfaChallengeView.class);
    }

}
