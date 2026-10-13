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
package ai.philterd.philter.data.entities;

import java.util.Date;

public class PendingDocumentEntity extends AbstractEncryptedEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";

    private String id;
    private String userId;
    private String documentId;
    private String fileName;
    private String inputMimeType;
    private String outputMimeType;
    private String policyName;
    // The policy version pinned when the request was accepted, so the deferred redaction is governed by
    // the version in force at request time (-1 when the policy could not be resolved at enqueue).
    private int policyVersion = -1;
    private String policyContentHash;
    private String effectiveJson;
    private String effectiveHash;
    public String getEffectiveJson() { return effectiveJson; }
    public void setEffectiveJson(String value) { effectiveJson = value; }
    public String getEffectiveHash() { return effectiveHash; }
    public void setEffectiveHash(String value) { effectiveHash = value; }

    private String contextName;
    // Opaque to Philter: documents of one account with different keys take turns in the queue.
    private String fairnessKey;
    // The address of the request that submitted the document, recorded on the audit events the worker writes.
    private String clientIpAddress;
    private String status;
    private String errorMessage;
    private byte[] input;
    private byte[] output;
    private Date submittedAt;
    private Date startedAt;
    private Date completedAt;
    private String claimedBy;
    private Date claimedAt;
    private String claimToken;
    private Date claimExpiresAt;
    /** How many times this job has been reclaimed after a worker failed to finish it. */
    private int reclaimCount;

    @Override
    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(final String documentId) {
        this.documentId = documentId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(final String fileName) {
        this.fileName = fileName;
    }

    public String getInputMimeType() {
        return inputMimeType;
    }

    public void setInputMimeType(final String inputMimeType) {
        this.inputMimeType = inputMimeType;
    }

    public String getOutputMimeType() {
        return outputMimeType;
    }

    public void setOutputMimeType(final String outputMimeType) {
        this.outputMimeType = outputMimeType;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(final String policyName) {
        this.policyName = policyName;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(final int policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getPolicyContentHash() {
        return policyContentHash;
    }

    public void setPolicyContentHash(final String policyContentHash) {
        this.policyContentHash = policyContentHash;
    }

    public String getContextName() {
        return contextName;
    }

    public void setContextName(final String contextName) {
        this.contextName = contextName;
    }

    public String getFairnessKey() {
        return fairnessKey;
    }

    public void setFairnessKey(final String fairnessKey) {
        this.fairnessKey = fairnessKey;
    }

    public String getClientIpAddress() {
        return clientIpAddress;
    }

    public void setClientIpAddress(final String clientIpAddress) {
        this.clientIpAddress = clientIpAddress;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(final String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public byte[] getInput() {
        return input;
    }

    public void setInput(final byte[] input) {
        this.input = input;
    }

    public byte[] getOutput() {
        return output;
    }

    public void setOutput(final byte[] output) {
        this.output = output;
    }

    public Date getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(final Date submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Date getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(final Date startedAt) {
        this.startedAt = startedAt;
    }

    public Date getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(final Date completedAt) {
        this.completedAt = completedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(final String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public int getReclaimCount() {
        return reclaimCount;
    }

    public void setReclaimCount(final int reclaimCount) {
        this.reclaimCount = reclaimCount;
    }

    public String getClaimToken() { return claimToken; }

    public void setClaimToken(final String claimToken) { this.claimToken = claimToken; }

    public Date getClaimExpiresAt() { return claimExpiresAt; }

    public void setClaimExpiresAt(final Date claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }

    public Date getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(final Date claimedAt) {
        this.claimedAt = claimedAt;
    }

}
