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
package ai.philterd.philter.services.diffuse;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

/**
 * The rules for recording PII counts, the same in every storage implementation: when recording is on, which
 * day a redaction counts toward, and that recording never affects the redaction. An implementation adds the
 * counts atomically.
 */
public abstract class StoredPiiCountAggregatePublisher implements PiiCountAggregatePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiiCountAggregatePublisher.class);

    private final AdminSettingsDataService adminSettingsDataService;

    protected StoredPiiCountAggregatePublisher(final AdminSettingsDataService adminSettingsDataService) {
        this.adminSettingsDataService = adminSettingsDataService;
    }

    /**
     * Adds one to the user's context's count for the day for each of the types, and one to its total, creating the
     * day's counts when there are none. Concurrent calls are all counted.
     */
    protected abstract void increment(final String userId, final String context, final Date bucketStart,
            final Set<String> piiTypes, final Date now);

    @Override
    public void record(final String userId, final String context, final Set<String> piiTypes) {

        if (piiTypes == null || piiTypes.isEmpty() || !isEnabled()) {
            return;
        }

        try {
            final Instant now = Instant.now();
            increment(userId, context, Date.from(now.truncatedTo(ChronoUnit.DAYS)), piiTypes, Date.from(now));
        } catch (final Exception ex) {
            LOGGER.debug("Unable to record PII count aggregate: {}", ex.getMessage());
        }
    }

    /**
     * Read on every redaction through the admin settings' own cache, which a settings change evicts, so
     * turning recording on or off applies at once on the instance that made the change.
     */
    private boolean isEnabled() {
        try {
            final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
            return settings != null && settings.isDiffuseCountsEnabled();
        } catch (final Exception ex) {
            LOGGER.debug("Unable to read admin settings for diffuse counts; treating as disabled: {}", ex.getMessage());
            return false;
        }
    }

}
