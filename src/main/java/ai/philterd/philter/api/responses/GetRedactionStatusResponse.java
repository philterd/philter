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

public class GetRedactionStatusResponse {

    private final String type;
    private final String status;
    private String error;
    public String getError() { return error; }
    public void setError(String value) { error = value; }
    private String effectiveConfigurationHash;
    public String getEffectiveConfigurationHash() { return effectiveConfigurationHash; }
    public void setEffectiveConfigurationHash(String value) { effectiveConfigurationHash = value; }
    private final String documentId;
    private String policyName;
    private Integer policyVersion;
    private String policyContentHash;

    public GetRedactionStatusResponse(final String type, final String status, final String documentId) {
        this.type = type;
        this.status = status;
        this.documentId = documentId;
    }

    /** What the job produces. redaction, a redacted document, is the only type. */
    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getDocumentId() {
        return documentId;
    }

    /** The policy the document was submitted with. Read that revision with GET /api/policies/{name}/versions/{revision}. */
    public String getPolicyName() {
        return policyName;
    }

    /** The policy's revision when the document was submitted, or null if none was recorded. */
    public Integer getPolicyVersion() {
        return policyVersion;
    }

    /** The SHA-256 of that revision's content. */
    public String getPolicyContentHash() {
        return policyContentHash;
    }

    /** Records the policy the document was submitted with. A negative version means none was recorded. */
    public void setPolicy(final String name, final int version, final String contentHash) {
        this.policyName = name;
        this.policyVersion = version < 0 ? null : version;
        this.policyContentHash = contentHash;
    }

}
