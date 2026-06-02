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

/**
 * Summary of an import of context entries: how many entries were in the payload and how each was
 * handled (newly inserted, overwritten, or skipped because the token already existed).
 */
public class ImportContextEntriesResponse {

    private final int total;
    private final int inserted;
    private final int overwritten;
    private final int skipped;

    public ImportContextEntriesResponse(final int total, final int inserted, final int overwritten, final int skipped) {
        this.total = total;
        this.inserted = inserted;
        this.overwritten = overwritten;
        this.skipped = skipped;
    }

    public int getTotal() {
        return total;
    }

    public int getInserted() {
        return inserted;
    }

    public int getOverwritten() {
        return overwritten;
    }

    public int getSkipped() {
        return skipped;
    }

}
