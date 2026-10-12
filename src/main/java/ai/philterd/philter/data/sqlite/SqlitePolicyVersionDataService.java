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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredPolicyVersionDataService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Stores policy versions in SQLite: content by its hash, and a row per revision. */
public class SqlitePolicyVersionDataService extends StoredPolicyVersionDataService {

    private final SqliteDatabase database;

    public SqlitePolicyVersionDataService(final SqliteDatabase database) {
        this.database = database;
    }

    private static PolicyVersionEntity read(final Sql.Row row) throws SQLException {
        final PolicyVersionEntity version = new PolicyVersionEntity();
        version.setId(row.string("id"));
        version.setUserId(row.string("user_id"));
        version.setName(row.string("name"));
        version.setRevision(row.intValue("revision"));
        version.setContentHash(row.string("content_hash"));
        version.setPolicy(row.string("policy"));
        version.setCapturedTimestamp(row.date("captured_timestamp"));
        version.setAuthorId(row.string("author_id"));
        return version;
    }

    @Override
    protected void insertContent(final String contentHash, final String policyJson) {
        database.write(connection -> Sql.update(connection, "INSERT INTO policy_contents (hash, policy) VALUES (?, ?)",
                contentHash, policyJson));
    }

    @Override
    protected String findContent(final String contentHash) {
        return database.read(connection -> Sql.first(connection, "SELECT policy FROM policy_contents WHERE hash = ?",
                row -> row.string("policy"), contentHash));
    }

    @Override
    protected void insertVersion(final PolicyVersionEntity version) {
        database.write(connection -> Sql.update(connection,
                "INSERT INTO policy_versions (id, user_id, name, revision, content_hash, policy, captured_timestamp, author_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                RecordIds.newId(), version.getUserId(), version.getName(), version.getRevision(), version.getContentHash(),
                version.getPolicy(), version.getCapturedTimestamp(), version.getAuthorId()));
    }

    private static String owner(final String userId) {
        return userId == null ? "user_id IS NULL" : "user_id = ?";
    }

    private static List<Object> ownerParams(final String userId, final Object... more) {
        final List<Object> params = new ArrayList<>();
        if (userId != null) {
            params.add(userId);
        }
        params.addAll(List.of(more));
        return params;
    }

    @Override
    public List<PolicyVersionEntity> findAllByName(final String name, final String userId, final int offset, final int limit) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM policy_versions WHERE " + owner(userId)
                + " AND name = ? ORDER BY revision DESC LIMIT ? OFFSET ?", SqlitePolicyVersionDataService::read,
                ownerParams(userId, name, limit, offset).toArray()));
    }

    @Override
    public Listings.Page<PolicyVersionEntity> listByName(final String name, final String userId, final boolean descending,
                                                         final int offset, final int limit) {
        return database.read(connection -> Sql.page(connection, "policy_versions", Sql.where(owner(userId), "name = ?"),
                ownerParams(userId, name), new Listings.Sort("revision", descending), offset, limit,
                SqlitePolicyVersionDataService::read));
    }

    @Override
    public PolicyVersionEntity findByNameAndRevision(final String name, final String userId, final int revision) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policy_versions WHERE " + owner(userId)
                + " AND name = ? AND revision = ?", SqlitePolicyVersionDataService::read,
                ownerParams(userId, name, revision).toArray()));
    }

    @Override
    public void recordDeletion(final String userId, final String name, final int revision, final String deletedBy,
                               final Date deletedAt) {
        database.write(connection -> Sql.update(connection,
                "INSERT INTO policy_deletions (user_id, name, revision, deleted_by, deleted_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (user_id, name) DO UPDATE SET revision = excluded.revision, "
                        + "deleted_by = excluded.deleted_by, deleted_at = excluded.deleted_at",
                userId, name, revision, deletedBy, deletedAt));
    }

    @Override
    public List<String> findNames(final String userId) {
        return database.read(connection -> Sql.query(connection,
                "SELECT DISTINCT name FROM policy_versions WHERE user_id = ?", row -> row.string("name"), userId));
    }

    @Override
    public Deletion findDeletion(final String userId, final String name) {
        return database.read(connection -> Sql.first(connection,
                "SELECT deleted_at, deleted_by FROM policy_deletions WHERE user_id = ? AND name = ?",
                row -> new Deletion(row.date("deleted_at"), row.string("deleted_by")), userId, name));
    }

}
