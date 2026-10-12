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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.StoredPolicyDataService;
import ai.philterd.philter.services.cache.RedactionCache;
import com.google.gson.Gson;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores policies in SQLite, one row per policy, with a revision counter per owner and name. */
public class SqlitePolicyDataService extends StoredPolicyDataService {

    private static final String UNCHANGED = " AND revision = ? AND description IS ? AND notes IS ?";

    private final SqliteDatabase database;

    public SqlitePolicyDataService(final SqliteDatabase database, final AuditEventPublisher auditEventPublisher, final Gson gson,
                                   final PolicyVersionDataService policyVersionDataService, final RedactionCache redactionCache) {
        super(auditEventPublisher, gson, policyVersionDataService, redactionCache);
        this.database = database;
    }

    private static PolicyEntity read(final Sql.Row row) throws SQLException {
        final PolicyEntity policy = new PolicyEntity();
        policy.setId(row.string("id"));
        policy.setUserId(row.string("user_id"));
        policy.setName(row.string("name"));
        policy.setPolicy(row.string("policy"));
        policy.setCreatedTimestamp(row.date("created_timestamp"));
        policy.setRevision(row.intValue("revision"));
        policy.setLastUpdatedTimestamp(row.date("last_updated_timestamp"));
        policy.setShared(row.bool("shared"));
        policy.setNotes(row.string("notes"));
        policy.setDescription(row.string("description"));
        policy.setManaged(row.bool("managed"));
        return policy;
    }

    @Override
    protected int reserveRevision(final String userId, final String name, final int minimum) {
        return database.write(connection -> {
            Sql.update(connection, "INSERT INTO policy_revision_counters (user_id, name, next) VALUES (?, ?, ?) "
                    + "ON CONFLICT (user_id, name) DO UPDATE SET next = max(next, excluded.next)",
                    userId == null ? "" : userId, name, minimum);
            Sql.update(connection, "UPDATE policy_revision_counters SET next = next + 1 WHERE user_id = ? AND name = ?",
                    userId == null ? "" : userId, name);
            return Sql.first(connection, "SELECT next FROM policy_revision_counters WHERE user_id = ? AND name = ?",
                    row -> row.intValue("next"), userId == null ? "" : userId, name) - 1;
        });
    }

    @Override
    protected String insert(final PolicyEntity policy) {
        final String id = RecordIds.newId();
        database.write(connection -> Sql.update(connection, "INSERT INTO policies (id, user_id, name, policy, created_timestamp, "
                + "revision, last_updated_timestamp, shared, notes, description, managed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, policy.getUserId(), policy.getName(), policy.getPolicy(), policy.getCreatedTimestamp(), policy.getRevision(),
                policy.getLastUpdatedTimestamp(), policy.isShared(), policy.getNotes(), policy.getDescription(), policy.isManaged()));
        return id;
    }

    private boolean replace(final PolicyEntity policy, final String condition, final Object... conditionValues) {
        final List<Object> params = new ArrayList<>(java.util.Arrays.asList(policy.getUserId(), policy.getName(),
                policy.getPolicy(), policy.getCreatedTimestamp(), policy.getRevision(), policy.getLastUpdatedTimestamp(),
                policy.isShared(), policy.getNotes(), policy.getDescription(), policy.isManaged(), policy.getId()));
        params.addAll(java.util.Arrays.asList(conditionValues));
        return database.write(connection -> Sql.update(connection, "UPDATE policies SET user_id = ?, name = ?, policy = ?, "
                + "created_timestamp = ?, revision = ?, last_updated_timestamp = ?, shared = ?, notes = ?, description = ?, "
                + "managed = ? WHERE id = ?" + condition, params.toArray())) == 1;
    }

    @Override
    protected boolean replaceIfUnchanged(final PolicyEntity policy, final int expectedRevision,
                                         final String expectedDescription, final String expectedNotes) {
        return replace(policy, UNCHANGED, expectedRevision, expectedDescription, expectedNotes);
    }

    @Override
    protected boolean replaceIfAtRevision(final PolicyEntity policy, final int expectedRevision) {
        return replace(policy, " AND revision = ?", expectedRevision);
    }

    @Override
    protected boolean updateDetailsIfUnchanged(final PolicyEntity policy, final String description, final String notes,
                                               final Date now) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (description != null) {
            sets.add("description = ?");
            params.add(description);
        }
        if (notes != null) {
            sets.add("notes = ?");
            params.add(notes);
        }
        sets.add("last_updated_timestamp = ?");
        params.addAll(java.util.Arrays.asList(now, policy.getId(), policy.getUserId(), policy.getRevision(),
                policy.getDescription(), policy.getNotes()));
        return database.write(connection -> Sql.update(connection, "UPDATE policies SET " + String.join(", ", sets)
                + " WHERE id = ? AND user_id = ?" + UNCHANGED, params.toArray())) == 1;
    }

    @Override
    protected boolean delete(final String userId, final String name) {
        return database.write(connection -> Sql.update(connection, "DELETE FROM policies WHERE user_id = ? AND name = ? "
                + "AND managed = 0", userId, name)) == 1;
    }

    @Override
    protected PolicyEntity findManaged(final String name) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policies WHERE name = ? AND managed = 1",
                SqlitePolicyDataService::read, name));
    }

    @Override
    protected Set<String> findNames(final String userId) {
        return database.read(connection -> new HashSet<>(Sql.query(connection, "SELECT name FROM policies WHERE user_id = ? "
                + "AND managed = 0", row -> row.string("name"), userId)));
    }

    @Override
    public PolicyEntity findOne(final String name, final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policies WHERE user_id = ? AND name = ?",
                SqlitePolicyDataService::read, userId, name));
    }

    @Override
    public PolicyEntity findOneById(final String id, final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policies WHERE id = ? AND "
                + "(user_id = ? OR shared = 1)", SqlitePolicyDataService::read, id, userId));
    }

    private Listings.Page<PolicyEntity> page(final String condition, final List<Object> params, final String q,
                                             final Listings.Sort sort, final int offset, final int limit) {
        final String where = Sql.where(condition, Sql.contains("name", q, params));
        return database.read(connection -> Sql.page(connection, "policies", where, params, sort, offset, limit,
                SqlitePolicyDataService::read));
    }

    @Override
    public Listings.Page<PolicyEntity> listOwn(final String userId, final String q, final Listings.Sort sort, final int offset,
                                               final int limit) {
        return page("(user_id = ? OR shared = 1) AND managed = 0", new ArrayList<>(List.of(userId)), q, sort, offset, limit);
    }

    @Override
    public Listings.Page<PolicyEntity> listAcrossUsers(final String q, final Listings.Sort sort, final int offset, final int limit) {
        return page("managed = 0", new ArrayList<>(), q, sort, offset, limit);
    }

    @Override
    public Listings.Page<PolicyEntity> listManaged(final String q, final Listings.Sort sort, final int offset, final int limit) {
        return page("managed = 1", new ArrayList<>(), q, sort, offset, limit);
    }

    @Override
    public int count(final String userId) {
        return (int) (long) database.read(connection -> userId == null
                ? Sql.count(connection, "SELECT COUNT(*) AS n FROM policies WHERE managed = 0")
                : Sql.count(connection, "SELECT COUNT(*) AS n FROM policies WHERE managed = 0 AND user_id = ?", userId));
    }

}
