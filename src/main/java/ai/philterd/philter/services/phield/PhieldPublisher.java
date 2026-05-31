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
 * <p>Publishing is disabled unless the {@code PHIELD_URL} environment variable is set, and it is
 * fire-and-forget: the request is sent asynchronously with a short timeout and any failure is
 * swallowed, so a slow or unavailable Phield can never affect redaction latency or availability.
 */
public class PhieldPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhieldPublisher.class);

    private final boolean enabled;
    private final String ingestUrl;
    private final String sourceId;
    private final String organization;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    // Tracks whether a publishing failure has already been logged at WARN. The first failure (and the
    // first after publishing recovers) is logged at WARN so a misconfigured or unreachable Phield is
    // noticeable; subsequent consecutive failures drop to DEBUG to avoid flooding the logs, since
    // publishing happens on every redaction.
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);

    public PhieldPublisher() {
        this(System.getenv().getOrDefault("PHIELD_URL", ""),
                System.getenv().getOrDefault("PHIELD_SOURCE_ID", "philter"),
                System.getenv().getOrDefault("PHIELD_ORGANIZATION", "philter"));
    }

    public PhieldPublisher(final String phieldUrl, final String sourceId, final String organization) {
        this.enabled = phieldUrl != null && !phieldUrl.isBlank();
        this.ingestUrl = enabled ? phieldUrl.replaceAll("/+$", "") + "/ingest" : null;
        this.sourceId = sourceId;
        this.organization = organization;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        if (enabled) {
            LOGGER.info("Phield publishing enabled; PII type counts (counts only, no PII) will be sent to {}", ingestUrl);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Asynchronously publishes the given PII type counts for a redaction context to Phield. Only the
     * counts are sent; no PII text is included. This is a no-op when publishing is disabled or when
     * there are no counts to report.
     *
     * @param context        The redaction context the counts belong to.
     * @param piiTypeCounts  A map of filter type name to the number of spans of that type.
     */
    public void publish(final String context, final Map<String, Integer> piiTypeCounts) {

        if (!enabled || piiTypeCounts == null || piiTypeCounts.isEmpty()) {
            return;
        }

        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(context, piiTypeCounts)))
                    .build();

            // Fire-and-forget: never block the redaction, and never let a failure reach the caller so
            // a slow or down Phield cannot affect Philter. Failures are logged (not propagated): a
            // non-2xx response is detected here because the JDK HttpClient completes the future
            // normally for any HTTP status, only failing it on transport-level errors.
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
     * Logs a publishing failure without ever propagating it. The first failure (and the first after a
     * recovery) is logged at WARN so the problem is noticeable; consecutive failures are logged at
     * DEBUG to avoid flooding the logs.
     */
    private void noteFailure(final String reason) {
        if (failureWarned.compareAndSet(false, true)) {
            LOGGER.warn("Unable to publish PII counts to Phield: {}. Further failures will be logged at debug until publishing succeeds again.", reason);
        } else {
            LOGGER.debug("Unable to publish PII counts to Phield: {}", reason);
        }
    }

    /**
     * Builds the Phield ingest JSON payload: {@code source_id}, {@code organization}, {@code context},
     * and the {@code pii_types} count map. Exposed at package scope for testing. The timestamp is
     * omitted because Phield defaults it to the time of receipt.
     */
    String buildPayload(final String context, final Map<String, Integer> piiTypeCounts) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_id", sourceId);
        payload.put("organization", organization);
        payload.put("context", context);
        payload.put("pii_types", piiTypeCounts);
        return gson.toJson(payload);
    }

}
