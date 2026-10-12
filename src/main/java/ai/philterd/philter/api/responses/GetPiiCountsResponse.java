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

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A page of PII counts, their total, and whether Philter is recording counts. */
public class GetPiiCountsResponse {

    private final boolean recordingEnabled;
    private final List<PiiCountView> counts;
    private final long total;

    public GetPiiCountsResponse(final boolean recordingEnabled, final List<PiiCountView> counts, final long total) {
        this.recordingEnabled = recordingEnabled;
        this.counts = counts;
        this.total = total;
    }

    @Schema(description = "Whether Philter is recording PII counts now (the diffuseCountsEnabled setting). When it "
            + "is false, no new counts are being recorded, so an empty listing does not mean no PII was found.")
    public boolean isRecordingEnabled() {
        return recordingEnabled;
    }

    @Schema(description = "The page of counts, one per context and day, most recent day first, then by context.")
    public List<PiiCountView> getCounts() {
        return counts;
    }

    @Schema(description = "How many context and day aggregates match the filters.")
    public long getTotal() {
        return total;
    }

}
