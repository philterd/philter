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

import ai.philterd.philter.data.services.AdmissionGuard;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.Date;

/** The queue admission guard in MongoDB: one document holding the token of the admission that holds it. */
final class QueueAdmission implements AdmissionGuard {

    private final MongoCollection<Document> state;

    QueueAdmission(final MongoClient client) {
        state = client.getDatabase("philter").getCollection("queue_admission")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }

    @Override
    public boolean tryAcquire(final String token) {
        try {
            state.updateOne(Filters.eq("_id", "queue"), Updates.setOnInsert("created_at", new Date()), new UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException race) {
            if (!MongoRecords.isDuplicate(race)) throw race;
        }
        return state.updateOne(Filters.and(Filters.eq("_id", "queue"), Filters.exists("token", false)),
                Updates.combine(Updates.set("token", token), Updates.set("started_at", new Date()))).getModifiedCount() == 1;
    }

    @Override
    public boolean release(final String token) {
        return state.updateOne(Filters.and(Filters.eq("_id", "queue"), Filters.eq("token", token)),
                Updates.combine(Updates.unset("token"), Updates.unset("started_at"))).getModifiedCount() == 1;
    }

}
