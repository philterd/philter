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

public class PolicyEntity extends AbstractEntity {

    private String id;
    private String policy;
    private String name;
    private Date createdTimestamp;
    private int revision;
    private Date lastUpdatedTimestamp;
    private boolean shared;
    private String notes;
    private String description;
    private boolean managed;
    private String userId;

    /**
     * Who is making the change being saved, recorded on the version snapshot it produces. Not stored
     * on the policy itself, so it is set for each write and is {@code null} for a policy read back.
     */
    private transient String authorId;

    /**
     * Counts changes to the description and notes, so a write can tell they changed since the policy was read without
     * comparing them: they are stored encrypted, and the same text never encrypts the same way twice. Stored with the
     * policy, but not part of it, so it is left out of the policy's JSON.
     */
    private transient int detailsRevision;

    public int getDetailsRevision() {
        return detailsRevision;
    }

    public void setDetailsRevision(final int detailsRevision) {
        this.detailsRevision = detailsRevision;
    }

    public void incrementRevision() {
        revision++;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
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

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(final String authorId) {
        this.authorId = authorId;
    }

    public String getUserId() {
        return userId;
    }

}
