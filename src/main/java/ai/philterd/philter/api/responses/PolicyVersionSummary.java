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
package ai.philterd.philter.api.responses;

import java.util.Date;

/** Lightweight summary of a retained policy version, used in the version-history list. */
public class PolicyVersionSummary {

    private final int revision;
    private final Date capturedTimestamp;
    private final String contentHash;

    public PolicyVersionSummary(final int revision, final Date capturedTimestamp,
                                 final String contentHash) {
        this.revision = revision;
        this.capturedTimestamp = capturedTimestamp;
        this.contentHash = contentHash;
    }

    public int getRevision() {
        return revision;
    }

    public Date getCapturedTimestamp() {
        return capturedTimestamp;
    }

    public String getContentHash() {
        return contentHash;
    }

}
