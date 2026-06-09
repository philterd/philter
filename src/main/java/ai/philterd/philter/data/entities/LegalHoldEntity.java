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

    private ObjectId id;
    private ObjectId userId;
    private String reference;
    private String scopeType;
    private String scopeValue;
    private String reason;
    private Date setAt;
    private ObjectId setByUserId;

    public static LegalHoldEntity fromDocument(final Document document) {
        final LegalHoldEntity entity = new LegalHoldEntity();
        entity.setId(document.getObjectId("_id"));
        entity.setUserId(document.getObjectId("user_id"));
        entity.setReference(document.getString("reference"));
        entity.setScopeType(document.getString("scope_type"));
        entity.setScopeValue(document.getString("scope_value"));
        entity.setReason(document.getString("reason"));
        entity.setSetAt(document.getDate("set_at"));
        entity.setSetByUserId(document.getObjectId("set_by_user_id"));
        return entity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("reference", reference);
        document.put("scope_type", scopeType);
        document.put("scope_value", scopeValue);
        document.put("reason", reason);
        document.put("set_at", setAt);
        document.put("set_by_user_id", setByUserId);
        return document;
    }

    @Override
    public ObjectId getId() { return id; }
    public void setId(final ObjectId id) { this.id = id; }

    public ObjectId getUserId() { return userId; }
    public void setUserId(final ObjectId userId) { this.userId = userId; }

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

    public ObjectId getSetByUserId() { return setByUserId; }
    public void setSetByUserId(final ObjectId setByUserId) { this.setByUserId = setByUserId; }
}
