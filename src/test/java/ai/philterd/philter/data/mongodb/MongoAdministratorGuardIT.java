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
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoAdministratorGuardIT extends AbstractMongoIT {

    /** Writes the record an instance that stopped while holding the guard leaves behind. */
    private void holdGuard(final Duration leaseLeft) {
        mongoClient.getDatabase("philter").getCollection("administrator_change_guard").insertOne(
                new Document("_id", "administrators").append("token", "another-instance")
                        .append("expires_at", new Date(System.currentTimeMillis() + leaseLeft.toMillis())));
    }

    @Test
    void aBusyGuardRefusesWithTheSecondsLeftOnTheHoldersLease() {
        holdGuard(Duration.ofSeconds(30));
        final MongoAdministratorGuard guard = new MongoAdministratorGuard(mongoClient, Duration.ofMillis(100));
        final AtomicBoolean ran = new AtomicBoolean();

        final AdministratorChangeInProgressException ex = assertThrows(AdministratorChangeInProgressException.class,
                () -> guard.execute(() -> ran.getAndSet(true)));

        assertFalse(ran.get(), "nothing runs without the guard");
        assertTrue(ex.getRetryAfterSeconds() >= 29 && ex.getRetryAfterSeconds() <= 30,
                "retry after " + ex.getRetryAfterSeconds());
    }

    @Test
    void anExpiredLeaseIsTakenOver() {
        holdGuard(Duration.ofSeconds(-1));
        final MongoAdministratorGuard guard = new MongoAdministratorGuard(mongoClient, Duration.ofMillis(100));

        assertEquals("ran", guard.execute(() -> "ran"));
    }

}
