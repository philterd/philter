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

import java.util.Date;

/**
 * Full representation of a legal hold returned by {@code GET /api/holds/{reference}}.
 */
public class LegalHoldResponse {

    private final String reference;
    private final String scopeType;
    private final String scopeValue;
    private final String reason;
    private final Date setAt;

    public LegalHoldResponse(final String reference, final String scopeType, final String scopeValue,
                              final String reason, final Date setAt) {
        this.reference = reference;
        this.scopeType = scopeType;
        this.scopeValue = scopeValue;
        this.reason = reason;
        this.setAt = setAt;
    }

    public String getReference() { return reference; }
    public String getScopeType() { return scopeType; }
    public String getScopeValue() { return scopeValue; }
    public String getReason() { return reason; }
    public Date getSetAt() { return setAt; }
}
