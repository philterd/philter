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

import com.vaadin.flow.server.CustomizedSystemMessages;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

/**
 * Sends the browser to the login page when a dashboard session expires, rather than showing Vaadin's
 * default "Session Expired" notification overlay.
 *
 * <p>This is the client-facing half of the inactivity timeout. The server-side half lives in
 * {@code application.properties}: {@code server.servlet.session.timeout} (driven by the
 * {@code SESSION_TIMEOUT_MINUTES} environment variable, default 15) sets how long a session may be
 * idle, and {@code vaadin.close-idle-sessions=true} makes Vaadin close idle sessions once that timeout
 * passes instead of keeping them alive with heartbeats. When the session is gone, the next interaction
 * from an open tab triggers the redirect configured here.
 */
@Component
public class SessionTimeoutConfigurer implements VaadinServiceInitListener {

    @Override
    public void serviceInit(final ServiceInitEvent event) {
        event.getSource().setSystemMessagesProvider(systemMessagesInfo -> {
            final CustomizedSystemMessages messages = new CustomizedSystemMessages();
            // Skip the "Session Expired" overlay and send the user straight back to the login page.
            messages.setSessionExpiredNotificationEnabled(false);
            messages.setSessionExpiredURL("login");
            return messages;
        });
    }

}
