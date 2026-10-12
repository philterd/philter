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
package ai.philterd.philter.data.services;

import ai.philterd.philter.model.ServiceResponse;

import java.util.function.Supplier;

/**
 * Serializes evidence deletion and legal hold changes for one owner, across every instance sharing the storage,
 * so a hold set while evidence is being deleted either blocks the deletion or is set after it.
 *
 * <p>A guard has no lease. An operation that fails part way leaves its guard held, because a deletion still
 * running in the storage cannot be fenced off; an operator clears it once the storage is quiet.
 */
public interface EvidenceGuard {

    /** Takes the owner's guard for an operation and returns its token, or null when the guard is already held. */
    String acquire(final String owner, final String operation);

    /** Releases the owner's guard if {@code token} holds it, and returns whether it did. */
    boolean release(final String owner, final String token);

    /**
     * Runs an action while holding the owner's guard, or refuses with 409 when another operation holds it. The
     * guard is released only after the action returns: an action that throws leaves it held.
     */
    default ServiceResponse execute(final String owner, final String operation, final Supplier<ServiceResponse> action) {
        if (owner == null) {
            return new ServiceResponse("Evidence owner is required.", false, 400);
        }
        final String token = acquire(owner, operation);
        if (token == null) {
            return new ServiceResponse("An evidence or legal-hold operation is active or requires recovery for this owner.",
                    false, 409, LegalHoldDataService.REASON_OPERATION_IN_PROGRESS);
        }
        // Deliberately not a finally block: a timeout can leave a storage-side mutation running.
        // Releasing after an uncertain failure would let a hold succeed before that mutation ends.
        final ServiceResponse result = action.get();
        if (!release(owner, token)) {
            throw new IllegalStateException("Evidence operation guard could not be released.");
        }
        return result;
    }

}
