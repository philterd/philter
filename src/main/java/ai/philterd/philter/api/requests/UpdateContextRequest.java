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
package ai.philterd.philter.api.requests;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code PATCH /api/contexts/{name}}. A setting left out keeps its current value. The names are
 * those {@code GET /api/contexts/{name}} returns.
 */
public class UpdateContextRequest {

    private Boolean entityTypeDisambiguation;
    private String disambiguationScope;
    private Boolean ledger;

    @Schema(description = "Whether to apply entity-type span disambiguation.")
    public Boolean getEntityTypeDisambiguation() { return entityTypeDisambiguation; }
    public void setEntityTypeDisambiguation(final Boolean value) { this.entityTypeDisambiguation = value; }

    @Schema(description = "Whether span disambiguation learns within each document (document) or across every "
            + "document redacted in the context (context).", allowableValues = {"document", "context"})
    public String getDisambiguationScope() { return disambiguationScope; }
    public void setDisambiguationScope(final String value) { this.disambiguationScope = value; }

    @Schema(description = "Whether to record redactions in the ledger.")
    public Boolean getLedger() { return ledger; }
    public void setLedger(final Boolean value) { this.ledger = value; }

}
