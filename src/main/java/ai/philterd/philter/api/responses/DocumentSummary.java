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

import java.util.Date;

/** A submitted document as {@code GET /api/documents} lists it. */
public class DocumentSummary {

    private final String type;

    private final String fileName;
    private final String status;
    private final Date timestamp;
    private final String documentId;
    private final String policyName;
    private final Integer policyVersion;
    private final String policyContentHash;

    /**
     * @param policyVersion The policy's revision when the document was submitted; negative means none was recorded.
     */
    public DocumentSummary(final String type, final String fileName, final String status, final Date timestamp,
                           final String documentId, final String policyName, final int policyVersion,
                           final String policyContentHash) {
        this.type = type;
        this.fileName = fileName;
        this.status = status;
        this.timestamp = timestamp;
        this.documentId = documentId;
        this.policyName = policyName;
        this.policyVersion = policyVersion < 0 ? null : policyVersion;
        this.policyContentHash = policyContentHash;
    }

    @Schema(description = "What the job produces. redaction, a redacted document, is the only type.")
    public String getType() {
        return type;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStatus() {
        return status;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public String getPolicyContentHash() {
        return policyContentHash;
    }

}
