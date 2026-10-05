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
package ai.philterd.philter.api.responses;

import ai.philterd.philter.data.entities.UserEntity;

/** A user's webhook configuration. Says whether a secret is set and never carries the secret. */
public class WebhookResponse {

    private final String url;
    private final boolean secretSet;

    public WebhookResponse(final UserEntity user) {
        this.url = user.getWebhookUrl();
        this.secretSet = user.getWebhookSecret() != null && !user.getWebhookSecret().isEmpty();
    }

    public String getUrl() { return url; }

    public boolean isSecretSet() { return secretSet; }

}
