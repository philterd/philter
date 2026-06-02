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
 * A single token-to-replacement mapping in a portable form. The original token is never present;
 * only its SHA-256 hash is, which is what redaction looks up. Because {@code tokenHash} is a plain
 * (unsalted) SHA-256 of the original token, the same token hashes identically in every environment,
 * so an exported mapping reproduces the same pseudonymization wherever it is imported.
 */
public class ContextEntryExport {

    private String tokenHash;
    private String replacement;
    private String filterType;
    private boolean replacementUuid;

    public ContextEntryExport() {
    }

    public ContextEntryExport(final String tokenHash, final String replacement, final String filterType, final boolean replacementUuid) {
        this.tokenHash = tokenHash;
        this.replacement = replacement;
        this.filterType = filterType;
        this.replacementUuid = replacementUuid;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(final String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(final String replacement) {
        this.replacement = replacement;
    }

    public String getFilterType() {
        return filterType;
    }

    public void setFilterType(final String filterType) {
        this.filterType = filterType;
    }

    public boolean isReplacementUuid() {
        return replacementUuid;
    }

    public void setReplacementUuid(final boolean replacementUuid) {
        this.replacementUuid = replacementUuid;
    }

}
