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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.services.EvidenceGuard;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.Date;
import java.util.UUID;

/** The evidence guard in MongoDB: a document per owner holding the token of the operation that holds it. */
public final class MongoEvidenceGuard implements EvidenceGuard {

    private final MongoCollection<Document> guards;

    public MongoEvidenceGuard(final MongoClient client) {
        guards = client.getDatabase("philter").getCollection("evidence_operation_guards")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }

    @Override
    public String acquire(final String owner, final String operation) {
        guards.updateOne(Filters.eq("_id", MongoIds.objectId(owner)), Updates.setOnInsert("created_at", new Date()),
                new UpdateOptions().upsert(true));
        final String token = UUID.randomUUID().toString();
        final boolean acquired = guards.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(owner)), Filters.exists("token", false)),
                Updates.combine(Updates.set("token", token), Updates.set("operation", operation),
                        Updates.set("started_at", new Date()))).getModifiedCount() == 1;
        return acquired ? token : null;
    }

    @Override
    public boolean release(final String owner, final String token) {
        return guards.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(owner)), Filters.eq("token", token)),
                Updates.combine(Updates.unset("token"), Updates.unset("operation"), Updates.unset("started_at")))
                .getModifiedCount() == 1;
    }

}
