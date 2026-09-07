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
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Serializes evidence deletion and hold changes for one owner across all instances.
 * No lease: MongoDB multi-document deletes cannot be fenced against a reclaimed lease.
 * An uncertain operation leaves its guard held until an operator establishes quiescence.
 */
final class EvidenceOperationGuard {
    private final MongoCollection<Document> guards;

    EvidenceOperationGuard(final MongoClient client) {
        guards = client.getDatabase("philter").getCollection("evidence_operation_guards")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }

    ServiceResponse execute(final ObjectId owner, final String operation, final Supplier<ServiceResponse> action) {
        if (owner == null) {
            return new ServiceResponse("Evidence owner is required.", false, 400);
        }
        guards.updateOne(Filters.eq("_id", owner), Updates.setOnInsert("created_at", new Date()),
                new UpdateOptions().upsert(true));
        final String token = UUID.randomUUID().toString();
        if (guards.updateOne(Filters.and(Filters.eq("_id", owner), Filters.exists("token", false)),
                Updates.combine(Updates.set("token", token), Updates.set("operation", operation),
                        Updates.set("started_at", new Date()))).getModifiedCount() != 1) {
            return new ServiceResponse("An evidence or legal-hold operation is active or requires recovery for this owner.",
                    false, 409);
        }
        // Deliberately not a finally block: a timeout can leave a server-side mutation running.
        // Releasing after an uncertain failure would let a hold succeed before that mutation ends.
        final ServiceResponse result = action.get();
        if (guards.updateOne(Filters.and(Filters.eq("_id", owner), Filters.eq("token", token)),
                Updates.combine(Updates.unset("token"), Updates.unset("operation"), Updates.unset("started_at")))
                .getModifiedCount() != 1) {
            throw new IllegalStateException("Evidence operation guard could not be released.");
        }
        return result;
    }
}
