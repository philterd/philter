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

import java.util.List;

/**
 * The portable representation of a context's token-to-replacement mapping table. This is the body
 * returned by the export endpoint and accepted by the import endpoint, so a mapping table can be
 * round-tripped between contexts, accounts, or environments to keep pseudonymization consistent.
 */
public class ContextEntriesExport {

    /** The current export schema version. Bumped only on a breaking format change. */
    public static final int CURRENT_VERSION = 1;

    private int version;
    private String context;
    private int count;
    private List<ContextEntryExport> entries;

    public ContextEntriesExport() {
    }

    public ContextEntriesExport(final String context, final List<ContextEntryExport> entries) {
        this.version = CURRENT_VERSION;
        this.context = context;
        this.entries = entries;
        this.count = entries != null ? entries.size() : 0;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(final int version) {
        this.version = version;
    }

    public String getContext() {
        return context;
    }

    public void setContext(final String context) {
        this.context = context;
    }

    public int getCount() {
        return count;
    }

    public void setCount(final int count) {
        this.count = count;
    }

    public List<ContextEntryExport> getEntries() {
        return entries;
    }

    public void setEntries(final List<ContextEntryExport> entries) {
        this.entries = entries;
    }

}
