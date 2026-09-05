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
import org.apache.hc.client5.http.DnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the webhook destination policy where it cannot be worked around: at the moment a connection
 * is made, once a name has become an address. Checking the URL alone is not enough, because the name
 * can resolve to something else by the time delivery runs.
 */
public final class WebhookDnsResolver implements DnsResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDnsResolver.class);

    private final AdminSettingsDataService adminSettingsDataService;

    public WebhookDnsResolver(final AdminSettingsDataService adminSettingsDataService) {
        this.adminSettingsDataService = adminSettingsDataService;
    }

    @Override
    public InetAddress[] resolve(final String host) throws UnknownHostException {

        final WebhookDestinationPolicy policy = policy();

        if (!policy.isHostAllowed(host)) {
            throw new UnknownHostException("Webhook delivery to " + host + " is not permitted by the administrator's allowlist.");
        }

        final List<InetAddress> permitted = new ArrayList<>();

        for (final InetAddress address : InetAddress.getAllByName(host)) {
            if (policy.isAddressAllowed(address)) {
                permitted.add(address);
            } else {
                LOGGER.warn("Refusing webhook delivery: {} resolved to {}, which the destination policy does not permit.",
                        host, address.getHostAddress());
            }
        }

        if (permitted.isEmpty()) {
            throw new UnknownHostException("Webhook delivery to " + host + " resolved only to addresses that are not permitted.");
        }

        return permitted.toArray(new InetAddress[0]);

    }

    @Override
    public String resolveCanonicalHostname(final String host) throws UnknownHostException {
        // Resolving through the same check, so this cannot be used to reach an address resolve refuses.
        return resolve(host)[0].getCanonicalHostName();
    }

    private WebhookDestinationPolicy policy() {
        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        return new WebhookDestinationPolicy(settings == null ? null : settings.getWebhookAllowlist());
    }

}
