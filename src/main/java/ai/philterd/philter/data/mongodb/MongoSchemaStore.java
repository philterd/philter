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

import ai.philterd.philter.data.schema.SchemaStore;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Keeps the schema version and its lock in the {@code schema_version} collection: a {@code version} document with
 * the version and the history of migrations run, and a {@code lock} document naming the instance migrating and
 * when its lock expires.
 */
public class MongoSchemaStore implements SchemaStore {

    static final String COLLECTION = "schema_version";
    private static final String VERSION = "version";
    private static final String LOCK = "lock";

    private final MongoDatabase database;
    private final MongoCollection<Document> collection;

    public MongoSchemaStore(final MongoClient mongoClient) {
        this.database = mongoClient.getDatabase("philter");
        this.collection = database.getCollection(COLLECTION)
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }

    @Override
    public Integer currentVersion() {
        final Document version = collection.find(Filters.eq("_id", VERSION)).first();
        return version == null ? null : version.getInteger(VERSION);
    }

    @Override
    public boolean hasData() {
        for (final String name : database.listCollectionNames()) {
            if (!name.equals(COLLECTION) && !name.startsWith("system.")
                    && database.getCollection(name).find().limit(1).first() != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void initialize(final int version, final Instant at) {
        collection.updateOne(Filters.eq("_id", VERSION),
                Updates.combine(Updates.set(VERSION, version), Updates.set("initialized_at", Date.from(at)),
                        Updates.set("updated_at", Date.from(at)), Updates.set("history", List.of())),
                new UpdateOptions().upsert(true));
    }

    @Override
    public void recordMigration(final int version, final String description, final Instant ranAt) {
        // One write, so the version never moves without its history entry, or the reverse.
        collection.updateOne(Filters.eq("_id", VERSION),
                Updates.combine(Updates.set(VERSION, version), Updates.set("updated_at", Date.from(ranAt)),
                        Updates.push("history", new Document(VERSION, version).append("description", description)
                                .append("ran_at", Date.from(ranAt)))),
                new UpdateOptions().upsert(true));
    }

    @Override
    public boolean acquireLock(final String owner, final Duration lease) {
        final Date now = new Date();
        try {
            // Matches a lock this owner holds or one that has expired; with neither, the upsert collides with the
            // held lock's id and fails, so the lock is not taken.
            collection.updateOne(
                    Filters.and(Filters.eq("_id", LOCK),
                            Filters.or(Filters.eq("owner", owner), Filters.lt("expires_at", now))),
                    Updates.combine(Updates.set("owner", owner), Updates.set("acquired_at", now),
                            Updates.set("expires_at", new Date(now.getTime() + lease.toMillis()))),
                    new UpdateOptions().upsert(true));
            return true;
        } catch (final MongoWriteException ex) {
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public void releaseLock(final String owner) {
        collection.deleteOne(Filters.and(Filters.eq("_id", LOCK), Filters.eq("owner", owner)));
    }

}
