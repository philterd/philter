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

public class PolicyEntity extends AbstractEntity {

    private ObjectId id;
    private String policy;
    private String name;
    private Date createdTimestamp;
    private int revision;
    private Date lastUpdatedTimestamp;
    private boolean shared;
    private String notes;
    private String description;
    private boolean managed;

    public static PolicyEntity fromDocument(final Document document) {
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setId(document.getObjectId("_id"));
        policyEntity.setPolicy(document.getString("policy"));
        policyEntity.setName(document.getString("name"));
        policyEntity.setUserId(document.getObjectId("user_id"));
        policyEntity.setCreatedTimestamp(document.getDate("created_timestamp"));
        policyEntity.setRevision(document.getInteger("revision", 0));
        policyEntity.setLastUpdatedTimestamp(document.getDate("last_updated_timestamp"));
        policyEntity.setShared(document.getBoolean("shared", false));
        policyEntity.setNotes(document.getString("notes"));
        policyEntity.setDescription(document.getString("description"));
        policyEntity.setManaged(document.getBoolean("managed", false));
        return policyEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("policy", policy);
        document.put("name", name);
        document.put("user_id", userId);
        document.put("created_timestamp", createdTimestamp);
        document.put("revision", revision);
        document.put("last_updated_timestamp", lastUpdatedTimestamp);
        document.put("shared", shared);
        document.put("notes", notes);
        document.put("description", description);
        document.put("managed", managed);
        return document;
    }

    public void incrementRevision() {
        revision++;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(Date createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Date getLastUpdatedTimestamp() {
        return lastUpdatedTimestamp;
    }

    public void setLastUpdatedTimestamp(Date lastUpdatedTimestamp) {
        this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isManaged() {
        return managed;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}
