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

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolver is where the policy is actually enforced: a URL check happens once, this happens on
 * every connection, after a name has become an address.
 */
class WebhookDnsResolverTest {

    private static WebhookDnsResolver resolverWith(final String allowlist) {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setWebhookAllowlist(allowlist);
        final AdminSettingsDataService adminSettings = mock(AdminSettingsDataService.class);
        when(adminSettings.findAdminSettings()).thenReturn(settings);
        return new WebhookDnsResolver(adminSettings);
    }

    @Test
    @DisplayName("Loopback is refused when nothing names it")
    void loopbackIsRefusedByDefault() {
        final UnknownHostException thrown =
                assertThrows(UnknownHostException.class, () -> resolverWith("").resolve("127.0.0.1"));
        assertEquals(true, thrown.getMessage().contains("127.0.0.1"));
    }

    @Test
    @DisplayName("The cloud metadata address is refused when nothing names it")
    void theMetadataAddressIsRefused() {
        assertThrows(UnknownHostException.class, () -> resolverWith("").resolve("169.254.169.254"));
    }

    @Test
    @DisplayName("A private address is refused when nothing names it")
    void aPrivateAddressIsRefused() {
        assertThrows(UnknownHostException.class, () -> resolverWith("").resolve("10.1.2.3"));
    }

    @Test
    @DisplayName("An address the administrator listed resolves")
    void aListedAddressResolves() throws Exception {
        final InetAddress[] resolved = resolverWith("127.0.0.1").resolve("127.0.0.1");
        assertEquals(1, resolved.length);
        assertEquals("127.0.0.1", resolved[0].getHostAddress());
    }

    @Test
    @DisplayName("With an allowlist, a host it does not name is refused before any lookup")
    void anUnlistedHostIsRefused() {
        assertThrows(UnknownHostException.class, () -> resolverWith("hooks.example.com").resolve("127.0.0.1"));
    }

    @Test
    @DisplayName("Canonical-hostname resolution goes through the same check")
    void canonicalResolutionIsAlsoChecked() {
        assertThrows(UnknownHostException.class, () -> resolverWith("").resolveCanonicalHostname("127.0.0.1"));
    }

}
