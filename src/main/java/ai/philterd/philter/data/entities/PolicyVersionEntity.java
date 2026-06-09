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
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * An immutable, append-only snapshot of a policy's JSON at a point in time, retained as governance
 * evidence so that the policy version stamped onto a redaction ledger entry can be resolved back to
 * the exact policy content that governed the redaction.
 *
 * <p>Snapshots are content-addressed by {@code contentHash} (a SHA-256 of the policy JSON): identical
 * content yields one snapshot regardless of name or revision, so a deleted-then-recreated policy that
 * reuses a name never collides with prior evidence. The {@code name} and {@code revision} recorded
 * here are those in force when the content was first captured; the authoritative name and version for
 * any given redaction live inline on the ledger entry.
 */
public class PolicyVersionEntity extends AbstractEntity {

    private ObjectId id;
    private String name;
    private int revision;
    private String contentHash;
    private String policy;
    private ObjectId userId;
    private Date capturedTimestamp;

    public static PolicyVersionEntity fromDocument(final Document document) {
        final PolicyVersionEntity entity = new PolicyVersionEntity();
        entity.setId(document.getObjectId("_id"));
        entity.setName(document.getString("name"));
        entity.setRevision(document.getInteger("revision", 0));
        entity.setContentHash(document.getString("content_hash"));
        entity.setPolicy(document.getString("policy"));
        entity.setUserId(document.getObjectId("user_id"));
        entity.setCapturedTimestamp(document.getDate("captured_timestamp"));
        return entity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("name", name);
        document.put("revision", revision);
        document.put("content_hash", contentHash);
        document.put("policy", policy);
        document.put("user_id", userId);
        document.put("captured_timestamp", capturedTimestamp);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(final ObjectId id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(final int revision) {
        this.revision = revision;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(final String contentHash) {
        this.contentHash = contentHash;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(final String policy) {
        this.policy = policy;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(final ObjectId userId) {
        this.userId = userId;
    }

    public Date getCapturedTimestamp() {
        return capturedTimestamp;
    }

    public void setCapturedTimestamp(final Date capturedTimestamp) {
        this.capturedTimestamp = capturedTimestamp;
    }

}
