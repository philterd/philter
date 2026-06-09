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

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import java.util.Date;

public class PendingDocumentEntity extends AbstractEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";

    private ObjectId id;
    private ObjectId userId;
    private String documentId;
    private String fileName;
    private String inputMimeType;
    private String outputMimeType;
    private String policyName;
    // The policy version pinned when the request was accepted, so the deferred redaction is governed by
    // the version in force at request time (-1 when the policy could not be resolved at enqueue).
    private int policyVersion = -1;
    private String policyContentHash;
    private String contextName;
    private String status;
    private String errorMessage;
    private byte[] input;
    private byte[] output;
    private Date submittedAt;
    private Date startedAt;
    private Date completedAt;
    private String claimedBy;
    private Date claimedAt;

    public static PendingDocumentEntity fromDocument(final Document document) {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setId(document.getObjectId("_id"));
        entity.setUserId(document.getObjectId("user_id"));
        entity.setDocumentId(document.getString("document_id"));
        entity.setFileName(document.getString("file_name"));
        entity.setInputMimeType(document.getString("input_mime_type"));
        entity.setOutputMimeType(document.getString("output_mime_type"));
        entity.setPolicyName(document.getString("policy_name"));
        entity.setPolicyVersion(document.getInteger("policy_version", -1));
        entity.setPolicyContentHash(document.getString("policy_content_hash"));
        entity.setContextName(document.getString("context_name"));
        entity.setStatus(document.getString("status"));
        entity.setErrorMessage(document.getString("error_message"));

        final Object inputBinary = document.get("input");
        if (inputBinary instanceof Binary) {
            entity.setInput(((Binary) inputBinary).getData());
        }

        final Object outputBinary = document.get("output");
        if (outputBinary instanceof Binary) {
            entity.setOutput(((Binary) outputBinary).getData());
        }

        entity.setSubmittedAt(document.getDate("submitted_at"));
        entity.setStartedAt(document.getDate("started_at"));
        entity.setCompletedAt(document.getDate("completed_at"));
        entity.setClaimedBy(document.getString("claimed_by"));
        entity.setClaimedAt(document.getDate("claimed_at"));
        return entity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("document_id", documentId);
        document.put("file_name", fileName);
        document.put("input_mime_type", inputMimeType);
        document.put("output_mime_type", outputMimeType);
        document.put("policy_name", policyName);
        document.put("policy_version", policyVersion);
        document.put("policy_content_hash", policyContentHash);
        document.put("context_name", contextName);
        document.put("status", status);
        document.put("error_message", errorMessage);
        if (input != null) {
            document.put("input", new Binary(input));
        }
        if (output != null) {
            document.put("output", new Binary(output));
        }
        document.put("submitted_at", submittedAt);
        document.put("started_at", startedAt);
        document.put("completed_at", completedAt);
        document.put("claimed_by", claimedBy);
        document.put("claimed_at", claimedAt);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(final ObjectId id) {
        this.id = id;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(final ObjectId userId) {
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

    public Date getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(final Date claimedAt) {
        this.claimedAt = claimedAt;
    }

}
