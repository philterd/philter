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

import ai.philterd.philter.data.services.AdministratorChangeInProgressException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lets one change at a time, across every Philter instance, check and change which users are active
 * administrators, so two changes cannot each see another administrator and together remove both. MongoDB has no
 * transaction across documents here, so the guard is one document holding the token of the change that holds it.
 * It is held for a lease: a guard left by an instance that stopped is free again once its lease ends.
 */
final class MongoAdministratorGuard {

    /** How long a holder keeps the guard; far longer than the count and write it covers. */
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** How long a change waits for another to finish before it gives up. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final String ID = "administrators";

    private final MongoCollection<Document> guards;
    private final Duration wait;

    MongoAdministratorGuard(final MongoClient client) {
        this(client, WAIT);
    }

    MongoAdministratorGuard(final MongoClient client, final Duration wait) {
        this.wait = wait;
        guards = client.getDatabase("philter").getCollection("administrator_change_guard")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }

    /**
     * Runs {@code work} holding the guard.
     *
     * @throws AdministratorChangeInProgressException when another change holds the guard for longer than the wait.
     */
    <T> T execute(final Supplier<T> work) {
        final String token = acquire();
        try {
            return work.get();
        } finally {
            guards.updateOne(Filters.and(Filters.eq("_id", ID), Filters.eq("token", token)),
                    Updates.combine(Updates.unset("token"), Updates.unset("expires_at")));
        }
    }

    private String acquire() {
        guards.updateOne(Filters.eq("_id", ID), Updates.setOnInsert("created_at", new Date()),
                new UpdateOptions().upsert(true));
        final String token = UUID.randomUUID().toString();
        final long giveUp = System.currentTimeMillis() + wait.toMillis();
        while (true) {
            final Date now = new Date();
            final boolean acquired = guards.updateOne(
                    Filters.and(Filters.eq("_id", ID),
                            Filters.or(Filters.exists("token", false), Filters.lt("expires_at", now))),
                    Updates.combine(Updates.set("token", token),
                            Updates.set("expires_at", new Date(now.getTime() + LEASE.toMillis()))))
                    .getModifiedCount() == 1;
            if (acquired) {
                return token;
            }
            if (System.currentTimeMillis() > giveUp) {
                throw new AdministratorChangeInProgressException(secondsLeftOnLease());
            }
            try {
                Thread.sleep(20);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to change the administrators.", ex);
            }
        }
    }

    /** Seconds until the current holder's lease ends, so the caller knows when the guard is free at the latest. */
    private long secondsLeftOnLease() {
        final Document guard = guards.find(Filters.eq("_id", ID)).first();
        final Date expiresAt = guard == null ? null : guard.getDate("expires_at");
        final long millis = expiresAt == null ? 0 : expiresAt.getTime() - System.currentTimeMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

}
