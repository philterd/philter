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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IP literals throughout, so no case depends on DNS. */
class WebhookSettingsTest {

    private static final String SECRET = "a-secret-of-16ch";

    @Test
    @DisplayName("A public http or https URL with a long enough secret is accepted")
    void acceptsAValidWebhook() {
        assertNull(WebhookSettings.validate("https://93.184.216.34/hook", SECRET, null));
        assertNull(WebhookSettings.validate("  http://93.184.216.34/hook  ", SECRET, ""));
    }

    @Test
    @DisplayName("Both a URL and a secret are required")
    void requiresBoth() {
        assertTrue(WebhookSettings.validate(null, SECRET, null).contains("required"));
        assertTrue(WebhookSettings.validate(" ", SECRET, null).contains("required"));
        assertTrue(WebhookSettings.validate("https://93.184.216.34/hook", "", null).contains("required"));
        assertTrue(WebhookSettings.validate("https://93.184.216.34/hook", null, null).contains("required"));
    }

    @Test
    @DisplayName("Only http and https URLs with a host are accepted")
    void refusesOtherUrls() {
        assertEquals("URL must start with http:// or https://", WebhookSettings.validate("ftp://93.184.216.34/x", SECRET, null));
        assertEquals("URL must start with http:// or https://", WebhookSettings.validate("93.184.216.34/x", SECRET, null));
        assertEquals("URL must include a host.", WebhookSettings.validate("https:///x", SECRET, null));
        assertTrue(WebhookSettings.validate("https://exa mple.com/x", SECRET, null).startsWith("Invalid URL"));
    }

    @Test
    @DisplayName("With no allowlist, private and loopback destinations are refused")
    void refusesLocalDestinationsByDefault() {
        assertEquals("Webhooks cannot be sent to a private or loopback address.",
                WebhookSettings.validate("https://127.0.0.1/hook", SECRET, null));
        assertEquals("Webhooks cannot be sent to a private or loopback address.",
                WebhookSettings.validate("https://10.0.0.5/hook", SECRET, ""));
    }

    @Test
    @DisplayName("An allowlist permits what it lists and refuses everything else")
    void appliesTheAllowlist() {
        assertNull(WebhookSettings.validate("https://10.4.1.2/hook", SECRET, "10.4.0.0/16"));
        assertEquals("Your administrator does not permit webhook delivery to 93.184.216.34.",
                WebhookSettings.validate("https://93.184.216.34/hook", SECRET, "10.4.0.0/16"));
    }

    @Test
    @DisplayName("A secret shorter than the minimum is refused")
    void refusesAShortSecret() {
        assertEquals("Secret must be at least 16 characters.",
                WebhookSettings.validate("https://93.184.216.34/hook", "fifteen-chars!!", null));
    }

}
