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
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredRedactListsDataService;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Stores each user's redact lists in SQLite, as one row per user. */
public class SqliteRedactListsDataService extends StoredRedactListsDataService {

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteRedactListsDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                        final AuditEventPublisher auditEventPublisher, final RedactionCache redactionCache) {
        super(auditEventPublisher, redactionCache);
        this.database = database;
        this.encryptionService = encryptionService;
    }

    private RedactListsEntity read(final Sql.Row row) throws SQLException {
        final RedactListsEntity entity = new RedactListsEntity();
        entity.setId(row.string("id"));
        entity.setUserId(row.string("user_id"));
        entity.setTermsToAlwaysRedact(SecretFields.openLines(encryptionService, row.string("terms_to_always_redact"),
                row.string("terms_to_always_redact_key")));
        entity.setTermsToNeverRedact(SecretFields.openLines(encryptionService, row.string("terms_to_never_redact"),
                row.string("terms_to_never_redact_key")));
        entity.setAlwaysRedactRevision(row.longValue("always_redact_revision"));
        entity.setNeverRedactRevision(row.longValue("never_redact_revision"));
        return entity;
    }

    private static String column(final RedactList list) {
        return list == RedactList.ALWAYS ? "terms_to_always_redact" : "terms_to_never_redact";
    }

    private static String revision(final RedactList list) {
        return list == RedactList.ALWAYS ? "always_redact_revision" : "never_redact_revision";
    }

    private static void ensureRow(final Connection connection, final String userId) throws SQLException {
        Sql.update(connection, "INSERT INTO redact_lists (id, user_id) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING",
                RecordIds.newId(), userId);
    }

    private void writeList(final Connection connection, final String userId, final RedactList list, final List<String> terms)
            throws SQLException {
        final SecretFields.Sealed sealed = SecretFields.sealLines(encryptionService, terms, userId);
        Sql.update(connection, "UPDATE redact_lists SET " + column(list) + " = ?, " + column(list) + "_key = ?, "
                + revision(list) + " = " + revision(list) + " + 1 WHERE user_id = ?", sealed.ciphertext(), sealed.key(), userId);
    }

    @Override
    protected void write(final String userId, final Map<RedactList, List<String>> lists) {
        database.write(connection -> {
            ensureRow(connection, userId);
            for (final Map.Entry<RedactList, List<String>> entry : lists.entrySet()) {
                writeList(connection, userId, entry.getKey(), entry.getValue());
            }
            return null;
        });
    }

    @Override
    protected ListContents writeIfAt(final String userId, final RedactList list, final List<String> terms,
                                     final Long expectedRevision) {
        return database.write(connection -> {
            final Long current = Sql.first(connection, "SELECT " + revision(list) + " AS r FROM redact_lists WHERE user_id = ?",
                    row -> row.longValue("r"), userId);
            if (expectedRevision != null && expectedRevision != (current == null ? 0L : current)) {
                return null;
            }
            ensureRow(connection, userId);
            writeList(connection, userId, list, terms);
            return contents(Sql.first(connection, "SELECT * FROM redact_lists WHERE user_id = ?", this::read, userId), list);
        });
    }

    @Override
    public RedactListsEntity find(final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM redact_lists WHERE user_id = ?", this::read, userId));
    }

    @Override
    public Revisions revisions(final String userId) {
        final Revisions revisions = database.read(connection -> Sql.first(connection, "SELECT always_redact_revision, "
                + "never_redact_revision FROM redact_lists WHERE user_id = ?", row -> new Revisions(
                row.longValue("always_redact_revision"), row.longValue("never_redact_revision")), userId));
        return revisions == null ? new Revisions(0, 0) : revisions;
    }

}
