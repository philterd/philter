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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

/**
 * Records aggregated, time-bucketed PII type counts to MongoDB for downstream differential-privacy
 * reporting with <a href="https://github.com/philterd/philterdiffuse">Philter Diffuse</a>.
 *
 * <p>Counts are <strong>document-presence</strong> counts: for each redaction, every distinct PII
 * type present increments that type's counter by one in the day's bucket for the context. This keeps
 * each redaction's contribution to any count at most one, which preserves the {@code sensitivity = 1}
 * assumption Diffuse's differential-privacy guarantee relies on. Only counts are stored; never any PII.
 *
 * <p>Recording is controlled by the global {@code diffuse_counts_enabled} admin setting (off by
 * default) and is best-effort: any failure is swallowed so it can never affect redaction.
 */
public class PiiCountAggregatePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiiCountAggregatePublisher.class);

    static final String COLLECTION = "pii_count_aggregates";
    private static final long ENABLED_CACHE_MILLIS = 30_000L;

    private final MongoCollection<Document> collection;
    private final AdminSettingsDataService adminSettingsDataService;

    // The admin setting is cached briefly so the common (disabled) path does not read admin_settings
    // from MongoDB on every redaction.
    private volatile boolean cachedEnabled = false;
    private volatile long cacheExpiresAt = 0L;

    public PiiCountAggregatePublisher(final MongoClient mongoClient, final AdminSettingsDataService adminSettingsDataService) {
        this.collection = mongoClient.getDatabase("philter").getCollection(COLLECTION);
        this.adminSettingsDataService = adminSettingsDataService;

        // One aggregate document per (context, daily bucket).
        try {
            collection.createIndex(Indexes.ascending("context", "bucket_start"), new IndexOptions().unique(true));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create index on {}: {}", COLLECTION, ex.getMessage());
        }
    }

    /**
     * Records the distinct PII types present in one redaction into the current day's aggregate for the
     * given context. No-op when disabled or when there are no PII types. Never throws.
     *
     * @param context   The redaction context.
     * @param piiTypes  The distinct PII type names present in the redaction.
     */
    public void record(final String context, final Set<String> piiTypes) {

        if (piiTypes == null || piiTypes.isEmpty() || !isEnabled()) {
            return;
        }

        try {
            final Instant now = Instant.now();
            final Date bucketStart = Date.from(now.truncatedTo(ChronoUnit.DAYS));

            final Document filter = new Document("context", context).append("bucket_start", bucketStart);

            // Increment each present type's count by exactly one (document-presence), plus the total.
            final Document increments = new Document("total_documents", 1);
            for (final String piiType : piiTypes) {
                increments.append("counts." + piiType, 1);
            }

            final Document update = new Document("$inc", increments)
                    .append("$set", new Document("updated_at", Date.from(now)));

            collection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (final Exception ex) {
            LOGGER.debug("Unable to record PII count aggregate: {}", ex.getMessage());
        }
    }

    private boolean isEnabled() {
        final long now = System.currentTimeMillis();
        if (now >= cacheExpiresAt) {
            try {
                final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
                cachedEnabled = settings != null && settings.isDiffuseCountsEnabled();
            } catch (final Exception ex) {
                LOGGER.debug("Unable to read admin settings for diffuse counts; treating as disabled: {}", ex.getMessage());
                cachedEnabled = false;
            }
            cacheExpiresAt = now + ENABLED_CACHE_MILLIS;
        }
        return cachedEnabled;
    }

}
