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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredLegalHoldDataService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Stores legal holds in SQLite, one row per hold. */
public class SqliteLegalHoldDataService extends StoredLegalHoldDataService {

    private final SqliteDatabase database;

    public SqliteLegalHoldDataService(final SqliteDatabase database, final AuditEventPublisher auditEventPublisher,
                                      final EvidenceGuard guard) {
        super(auditEventPublisher, guard);
        this.database = database;
    }

    private static LegalHoldEntity read(final Sql.Row row) throws SQLException {
        final LegalHoldEntity hold = new LegalHoldEntity();
        hold.setId(row.string("id"));
        hold.setUserId(row.string("user_id"));
        hold.setReference(row.string("reference"));
        hold.setScopeType(row.string("scope_type"));
        hold.setScopeValue(row.string("scope_value"));
        hold.setReason(row.string("reason"));
        hold.setSetAt(row.date("set_at"));
        hold.setSetByUserId(row.string("set_by_user_id"));
        return hold;
    }

    @Override
    protected void insert(final LegalHoldEntity hold) {
        database.write(connection -> Sql.update(connection,
                "INSERT INTO legal_holds (id, user_id, reference, scope_type, scope_value, reason, set_at, set_by_user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                RecordIds.newId(), hold.getUserId(), hold.getReference(), hold.getScopeType(), hold.getScopeValue(),
                hold.getReason(), hold.getSetAt(), hold.getSetByUserId()));
    }

    @Override
    protected void delete(final String reference, final String userId) {
        database.write(connection -> Sql.update(connection, "DELETE FROM legal_holds WHERE user_id = ? AND reference = ?",
                userId, reference));
    }

    @Override
    public LegalHoldEntity findByReference(final String reference, final String userId) {
        return database.read(connection -> Sql.first(connection,
                "SELECT * FROM legal_holds WHERE user_id = ? AND reference = ?", SqliteLegalHoldDataService::read, userId, reference));
    }

    @Override
    public Listings.Page<LegalHoldEntity> list(final String userId, final String q, final Listings.Sort sort,
                                               final int offset, final int limit) {
        final List<Object> params = new ArrayList<>();
        String owner = null;
        if (userId != null) {
            owner = "user_id = ?";
            params.add(userId);
        }
        final String where = Sql.where(owner, Sql.contains("reference", q, params));
        return database.read(connection -> Sql.page(connection, "legal_holds", where, params, sort, offset, limit,
                SqliteLegalHoldDataService::read));
    }

    @Override
    public List<LegalHoldEntity> findBlockingHoldsForDocument(final String userId, final String documentId) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM legal_holds WHERE user_id = ? AND "
                        + "((scope_type = ? AND scope_value = ?) OR scope_type = ?)", SqliteLegalHoldDataService::read,
                userId, LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, documentId, LegalHoldEntity.SCOPE_USER));
    }

    @Override
    public List<LegalHoldEntity> findAllHoldsForUser(final String userId) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM legal_holds WHERE user_id = ?",
                SqliteLegalHoldDataService::read, userId));
    }

}
