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

import java.util.Set;

/**
 * Records aggregated, time-bucketed PII type counts for downstream differential-privacy
 * reporting with <a href="https://github.com/philterd/philter-diffuse">Philter Diffuse</a>.
 *
 * <p>Counts are <strong>document-presence</strong> counts: for each redaction, every distinct PII
 * type present increments that type's counter by one in the day's bucket for the user's context. Counts are kept per
 * user, since context names are unique only per user and two users' contexts must not be counted together. This keeps
 * each redaction's contribution to any count at most one, which preserves the {@code sensitivity = 1}
 * assumption Diffuse's differential-privacy guarantee relies on. Only counts are stored; never any PII.
 *
 * <p>Recording is controlled by the global {@code diffuse_counts_enabled} admin setting (off by
 * default) and is best-effort: any failure is swallowed so it can never affect redaction.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Counts are incremented atomically, so concurrent redactions in the same context and day are all
 *   counted.</li>
 *   <li>Each redaction adds at most one to each type's count, and recording never affects the redaction: it is
 *   a no-op when disabled and never throws.</li>
 * </ul>
 */
public interface PiiCountAggregatePublisher {

    /**
     * Records the distinct PII types present in one redaction into the current day's aggregate for the
     * user's context. No-op when disabled or when there are no PII types. Never throws.
     *
     * @param userId    The user whose redaction it was.
     * @param context   The redaction context.
     * @param piiTypes  The distinct PII type names present in the redaction.
     */
    void record(String userId, String context, Set<String> piiTypes);

}
