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

import ai.philterd.philter.data.entities.PolicyEntity;

import java.util.Date;

/** A policy's details: everything about it except the policy itself. */
public class PolicyDetailsResponse {

    private final String name;
    private final String description;
    private final String notes;
    private final int revision;
    private final boolean managed;
    private final Date created;
    private final Date lastUpdated;

    public PolicyDetailsResponse(final PolicyEntity policy) {
        this.name = policy.getName();
        this.description = policy.getDescription();
        this.notes = policy.getNotes();
        this.revision = policy.getRevision();
        this.managed = policy.isManaged();
        this.created = policy.getCreatedTimestamp();
        this.lastUpdated = policy.getLastUpdatedTimestamp();
    }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getNotes() { return notes; }

    /** The policy's current version; see the version history endpoints. */
    public int getRevision() { return revision; }

    /** Whether this is a built-in managed policy, which can be read and copied but not changed. */
    public boolean isManaged() { return managed; }

    public Date getCreated() { return created; }

    public Date getLastUpdated() { return lastUpdated; }

}
