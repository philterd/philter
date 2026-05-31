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
package ai.philterd.philter.services.phield;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optionally publishes per-redaction PII type counts to a <a href="https://github.com/philterd/phield">Phield</a>
 * drift monitor. Only counts are sent (for example {@code {"SSN": 6}}), never any matched text, so no
 * PII ever leaves Philter.
 *
 * <p>Whether to publish, the Phield endpoint, and the reported source/organization are configured by
 * an administrator (see the Admin settings); they are read from {@code admin_settings} and cached
 * briefly so the common (disabled) path does no per-redaction lookup. Publishing is fire-and-forget:
 * the request is sent asynchronously with a short timeout and any failure is swallowed, so a slow or
 * unavailable Phield can never affect redaction latency or availability.
 */
public class PhieldPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhieldPublisher.class);

    private static final long CONFIG_CACHE_MILLIS = 30_000L;

    private final AdminSettingsDataService adminSettingsDataService;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    // The first failure (and the first after publishing recovers) is logged at WARN so a misconfigured
    // or unreachable Phield is noticeable; consecutive failures drop to DEBUG to avoid flooding.
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    private volatile Config cachedConfig = Config.DISABLED;
    private volatile long cacheExpiresAt = 0L;

    public PhieldPublisher(final AdminSettingsDataService adminSettingsDataService) {
        this.adminSettingsDataService = adminSettingsDataService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * Asynchronously publishes the given PII type counts for a redaction context to Phield. Only the
     * counts are sent; no PII text is included. No-op when publishing is disabled or when there are no
     * counts to report.
     */
    public void publish(final String context, final Map<String, Integer> piiTypeCounts) {

        if (piiTypeCounts == null || piiTypeCounts.isEmpty()) {
            return;
        }

        final Config config = config();
        if (!config.enabled) {
            return;
        }

        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(config.ingestUrl))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(context, piiTypeCounts, config.sourceId, config.organization)))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() / 100 == 2) {
                            failureWarned.set(false);
                        } else {
                            noteFailure("Phield returned HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        noteFailure(ex.getMessage());
                        return null;
                    });
        } catch (final Exception ex) {
            noteFailure(ex.getMessage());
        }
    }

    /**
     * Builds the Phield ingest JSON payload: {@code source_id}, {@code organization}, {@code context},
     * and the {@code pii_types} count map. Exposed at package scope for testing. The timestamp is
     * omitted because Phield defaults it to the time of receipt.
     */
    String buildPayload(final String context, final Map<String, Integer> piiTypeCounts, final String sourceId, final String organization) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_id", sourceId);
        payload.put("organization", organization);
        payload.put("context", context);
        payload.put("pii_types", piiTypeCounts);
        return gson.toJson(payload);
    }

    /** Whether publishing is currently enabled (a Phield URL is configured and the toggle is on). */
    boolean isEnabled() {
        return config().enabled;
    }

    private Config config() {
        final long now = System.currentTimeMillis();
        if (now >= cacheExpiresAt) {
            try {
                cachedConfig = Config.from(adminSettingsDataService.findAdminSettings());
            } catch (final Exception ex) {
                LOGGER.debug("Unable to read admin settings for Phield; treating as disabled: {}", ex.getMessage());
                cachedConfig = Config.DISABLED;
            }
            cacheExpiresAt = now + CONFIG_CACHE_MILLIS;
        }
        return cachedConfig;
    }

    private void noteFailure(final String reason) {
        if (failureWarned.compareAndSet(false, true)) {
            LOGGER.warn("Unable to publish PII counts to Phield: {}. Further failures will be logged at debug until publishing succeeds again.", reason);
        } else {
            LOGGER.debug("Unable to publish PII counts to Phield: {}", reason);
        }
    }

    /** Snapshot of the Phield configuration resolved from admin settings. */
    private static final class Config {

        private static final Config DISABLED = new Config(false, null, "philter", "philter");

        private final boolean enabled;
        private final String ingestUrl;
        private final String sourceId;
        private final String organization;

        private Config(final boolean enabled, final String ingestUrl, final String sourceId, final String organization) {
            this.enabled = enabled;
            this.ingestUrl = ingestUrl;
            this.sourceId = sourceId;
            this.organization = organization;
        }

        private static Config from(final AdminSettingsEntity settings) {
            if (settings == null) {
                return DISABLED;
            }
            final String url = settings.getPhieldUrl();
            final boolean enabled = settings.isPhieldEnabled() && url != null && !url.isBlank();
            if (!enabled) {
                return DISABLED;
            }
            final String ingestUrl = url.replaceAll("/+$", "") + "/ingest";
            final String sourceId = blankToDefault(settings.getPhieldSourceId());
            final String organization = blankToDefault(settings.getPhieldOrganization());
            return new Config(true, ingestUrl, sourceId, organization);
        }

        private static String blankToDefault(final String value) {
            return (value == null || value.isBlank()) ? "philter" : value;
        }

    }

}
