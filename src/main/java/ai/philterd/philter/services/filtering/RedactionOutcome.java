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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.model.filtering.AbstractFilterResult;

/**
 * A redaction's result, the policy version that governed it, and the id it was recorded under.
 *
 * <p>{@link #documentId()} is what the ledger chain and audit trail were written under, so it is the
 * id to return to the caller: {@code GET /api/ledger/{documentId}} resolves it.
 */
public record RedactionOutcome(String documentId, AbstractFilterResult result, AppliedPolicy appliedPolicy) {
}
