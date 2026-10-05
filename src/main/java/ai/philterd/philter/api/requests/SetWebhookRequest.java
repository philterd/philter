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
package ai.philterd.philter.api.requests;

/** Request body for {@code PUT /api/webhook}. */
public class SetWebhookRequest {

    private String url;
    private String secret;

    public String getUrl() { return url; }
    public void setUrl(final String url) { this.url = url; }

    public String getSecret() { return secret; }
    public void setSecret(final String secret) { this.secret = secret; }

}
