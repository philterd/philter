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

import ai.philterd.philter.services.cache.LoginAttemptCache;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Listens for Spring Security authentication events to drive UI login lockout: a failed login
 * increments the per-username failure count, and a successful login clears it. The
 * {@link LoginAttemptCache} enforces the threshold; the {@code UserDetailsService} consults it so a
 * locked account is rejected before its password is checked.
 */
@Component
public class LoginAttemptListener {

    private final LoginAttemptCache loginAttemptCache;

    public LoginAttemptListener(final LoginAttemptCache loginAttemptCache) {
        this.loginAttemptCache = loginAttemptCache;
    }

    @EventListener
    public void onFailure(final AuthenticationFailureBadCredentialsEvent event) {
        final String username = String.valueOf(event.getAuthentication().getName());
        loginAttemptCache.recordFailure(username);
    }

    @EventListener
    public void onSuccess(final AuthenticationSuccessEvent event) {
        final String username = String.valueOf(event.getAuthentication().getName());
        loginAttemptCache.reset(username);
    }

}
