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
 * An active legal hold that blocks deletion or purge of the evidence it covers.
 *
 * <p>Each hold has a caller-supplied {@code reference} that is unique per owner (user_id). Multiple
 * independent holds may coexist on the same evidence — releasing one hold does not expose evidence
 * still covered by another.
 *
 * <p>Two scope types are supported:
 * <ul>
 *   <li>{@code document_chain} — protects all ledger entries for a specific document ID.</li>
 *   <li>{@code user} — protects all governance evidence (ledger entries) owned by the user.</li>
 * </ul>
 */
public class LegalHoldEntity extends AbstractEntity {

    /** Scope type value for a hold that protects a single document's ledger chain. */
    public static final String SCOPE_DOCUMENT_CHAIN = "document_chain";

    /** Scope type value for a hold that protects all evidence owned by the user. */
    public static final String SCOPE_USER = "user";

    private String id;
    private String userId;
    private String reference;
    private String scopeType;
    private String scopeValue;
    private String reason;
    private Date setAt;
    private String setByUserId;

    @Override
    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(final String userId) { this.userId = userId; }

    public String getReference() { return reference; }
    public void setReference(final String reference) { this.reference = reference; }

    public String getScopeType() { return scopeType; }
    public void setScopeType(final String scopeType) { this.scopeType = scopeType; }

    public String getScopeValue() { return scopeValue; }
    public void setScopeValue(final String scopeValue) { this.scopeValue = scopeValue; }

    public String getReason() { return reason; }
    public void setReason(final String reason) { this.reason = reason; }

    public Date getSetAt() { return setAt; }
    public void setSetAt(final Date setAt) { this.setAt = setAt; }

    public String getSetByUserId() { return setByUserId; }
    public void setSetByUserId(final String setByUserId) { this.setByUserId = setByUserId; }
}
