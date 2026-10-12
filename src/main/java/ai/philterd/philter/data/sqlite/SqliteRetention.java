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
package ai.philterd.philter.data.sqlite;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;
import java.util.List;

/**
 * Removes records past their retention on a schedule, as MongoDB's TTL indexes do, so they do not outlive it while no
 * new records are written: finished asynchronous documents, finished webhook deliveries, and expired sign-in
 * challenges. Evidence (the ledger, legal holds, and the audit log) is never removed this way.
 */
public class SqliteRetention {

    private static final Logger LOGGER = LogManager.getLogger(SqliteRetention.class);

    /** A store whose records expire. */
    public interface Expiring {

        /** Removes the records past their retention. */
        void removeExpired();

    }

    private final List<Expiring> stores;

    /** Applies to each of {@code services} that is a SQLite store whose records expire; others are skipped. */
    public SqliteRetention(final Object... services) {
        this.stores = Arrays.stream(services).filter(Expiring.class::isInstance).map(Expiring.class::cast)
                .toList();
    }

    @Scheduled(fixedDelayString = "${philter.sqlite.retention-interval-ms:60000}", initialDelay = 10000)
    public void removeExpired() {
        for (final Expiring store : stores) {
            try {
                store.removeExpired();
            } catch (final RuntimeException ex) {
                // The next run tries again; one store failing must not stop the others.
                LOGGER.warn("Removing expired records from {} failed.", store.getClass().getSimpleName(), ex);
            }
        }
    }

}
