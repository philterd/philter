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
package ai.philterd.philter.services.webhook;

import java.net.URI;

/**
 * The rules a user's webhook URL and secret must meet when they are saved, shared by every caller
 * that saves one. The destination rule is enforced again at delivery.
 */
public final class WebhookSettings {

    /** The shortest secret accepted for HMAC-signing deliveries. */
    public static final int MIN_SECRET_LENGTH = 16;

    private WebhookSettings() {
    }

    /**
     * Returns why the URL and secret cannot be saved, or {@code null} when they can.
     *
     * @param allowlist The administrator's webhook destination allowlist, or {@code null} for none.
     */
    public static String validate(final String url, final String secret, final String allowlist) {

        if (url == null || url.isBlank() || secret == null || secret.isEmpty()) {
            return "Both a URL and a secret are required. Remove the webhook to clear it.";
        }

        // Any failure parsing the URL or resolving its host is reported as an invalid URL rather than
        // escaping as an error.
        try {

            final URI parsed = URI.create(url.trim());

            if (parsed.getScheme() == null || (!parsed.getScheme().equalsIgnoreCase("http")
                    && !parsed.getScheme().equalsIgnoreCase("https"))) {
                return "URL must start with http:// or https://";
            }

            if (parsed.getHost() == null) {
                return "URL must include a host.";
            }

            final WebhookDestinationPolicy policy = new WebhookDestinationPolicy(allowlist);
            if (!policy.isDestinationAllowed(parsed.getHost())) {
                return policy.isEmpty()
                        ? "Webhooks cannot be sent to a private or loopback address."
                        : "Your administrator does not permit webhook delivery to " + parsed.getHost() + ".";
            }

        } catch (final RuntimeException ex) {
            return "Invalid URL: " + ex.getMessage();
        }

        if (secret.length() < MIN_SECRET_LENGTH) {
            return "Secret must be at least " + MIN_SECRET_LENGTH + " characters.";
        }

        return null;

    }

}
