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

public class ContextEntryView {

    private final String id;
    private final String replacement;
    private final String filterType;
    private final long reads;
    private final Date timestamp;

    public ContextEntryView(final String id, final String replacement, final String filterType, final long reads, final Date timestamp) {
        this.id = id;
        this.replacement = replacement;
        this.filterType = filterType;
        this.reads = reads;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getReplacement() {
        return replacement;
    }

    public String getFilterType() {
        return filterType;
    }

    public long getReads() {
        return reads;
    }

    public Date getTimestamp() {
        return timestamp;
    }

}
