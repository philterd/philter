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

/**
 * An immutable, append-only snapshot of a policy's JSON at a point in time, retained as governance
 * evidence so that the policy version stamped onto a redaction ledger entry can be resolved back to
 * the exact policy content that governed the redaction.
 *
 * <p>This is a revision reference. Content is also retained independently in policy_contents,
 * keyed by SHA-256. Owner/name revision numbers continue across deletion and name reuse.
 */
public class PolicyVersionEntity extends AbstractEntity {

    private String id;
    private String name;
    private int revision;
    private String contentHash;
    private String policy;
    private String userId;
    private Date capturedTimestamp;
    private String authorId;

    /**
     * Whether the policy was ever at this revision. A conditional write snapshots its revision before
     * it writes, so that a live revision always has its snapshot, and marks it applied once the write
     * lands; a refused write leaves a snapshot that is not applied, which listings do not show.
     */
    private boolean applied = true;

    @Override
    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    /** The user whose change produced this revision, or {@code null} when it is not known. */
    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(final String authorId) {
        this.authorId = authorId;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    public Date getCapturedTimestamp() {
        return capturedTimestamp;
    }

    public void setCapturedTimestamp(final Date capturedTimestamp) {
        this.capturedTimestamp = capturedTimestamp;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(final boolean applied) {
        this.applied = applied;
    }

}
