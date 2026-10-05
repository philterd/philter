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
package ai.philterd.philter.services.apikeys;

import ai.philterd.philter.config.SessionKeyConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires session keys that nobody uses again. A key presented after it lapses is expired by the
 * authentication filter; this catches the rest, so every expiry is recorded in the audit log.
 */
@Component
public class SessionKeyExpiryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionKeyExpiryWorker.class);

    private final ApiKeyDataService apiKeyDataService;

    public SessionKeyExpiryWorker(final ApiKeyDataService apiKeyDataService) {
        // Here rather than at first sign-in, so a bad value stops startup.
        SessionKeyConfig.validate();
        this.apiKeyDataService = apiKeyDataService;
    }

    @Scheduled(fixedDelayString = "${philter.session-keys.sweep-interval-ms:60000}", initialDelay = 30000)
    public void sweep() {

        try {
            final long expired = apiKeyDataService.expireSessionKeys(null);
            if (expired > 0) {
                LOGGER.info("Expired {} session key(s).", expired);
            }
        } catch (Exception ex) {
            LOGGER.error("Session key expiry sweep failed", ex);
        }

    }

}
