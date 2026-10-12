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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.model.AuditLogEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Clears the queue admission guard and the evidence guards that a Philter left held when it stopped part way
 * through an operation. With MongoDB a held guard may belong to another instance that is still running, so it waits
 * for an operator. SQLite storage has one instance, and this runs only while it holds {@link SqliteInstanceLock}, so
 * a guard still held at startup can only have been left by a Philter that has stopped.
 */
public final class SqliteGuardRecovery {

    private static final Logger LOGGER = LogManager.getLogger(SqliteGuardRecovery.class);

    /** A guard that was held and has been cleared. */
    public record Recovered(String guard, String owner, String operation, Date startedAt) {
    }

    private SqliteGuardRecovery() {
    }

    /**
     * Clears every held guard, logging and auditing each one, and returns what it cleared. Call it only while
     * holding the instance lock and before any service can take a guard.
     */
    public static List<Recovered> recover(final SqliteDatabase database, final AuditEventPublisher auditEventPublisher) {

        final List<Recovered> recovered = database.write(connection -> {
            final List<Recovered> held = new ArrayList<>();
            held.addAll(Sql.query(connection, "SELECT started_at FROM queue_admission WHERE token IS NOT NULL",
                    row -> new Recovered("queue_admission", null, null, row.date("started_at"))));
            held.addAll(Sql.query(connection, "SELECT owner, operation, started_at FROM evidence_guards WHERE token IS NOT NULL",
                    row -> new Recovered("evidence", row.string("owner"), row.string("operation"), row.date("started_at"))));
            Sql.update(connection, "UPDATE queue_admission SET token = NULL, started_at = NULL WHERE token IS NOT NULL");
            Sql.update(connection, "UPDATE evidence_guards SET token = NULL, operation = NULL, started_at = NULL "
                    + "WHERE token IS NOT NULL");
            return held;
        });

        for (final Recovered guard : recovered) {
            final String details = "guard: " + guard.guard()
                    + (guard.owner() == null ? "" : ", owner: " + guard.owner())
                    + (guard.operation() == null ? "" : ", operation: " + guard.operation())
                    + ", held since: " + (guard.startedAt() == null ? "unknown" : guard.startedAt().toInstant())
                    + ", reason: left held by a Philter that stopped";
            LOGGER.warn("Cleared a storage guard left held by a Philter that stopped ({}). Check that the operation it "
                    + "guarded finished as expected.", details);
            auditEventPublisher.auditEvent("startup", AuditLogEvent.STORAGE_GUARD_RECOVERED, guard.owner(), null,
                    "system", details);
        }

        return recovered;

    }

}
