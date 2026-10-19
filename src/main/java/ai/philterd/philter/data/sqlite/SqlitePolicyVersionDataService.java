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
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredPolicyVersionDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Stores policy versions in SQLite: content by its hash, and a row per revision. */
public class SqlitePolicyVersionDataService extends StoredPolicyVersionDataService {

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqlitePolicyVersionDataService(final SqliteDatabase database, final EncryptionService encryptionService) {
        this.database = database;
        this.encryptionService = encryptionService;
    }

    // Each revision's policy is encrypted, as the live policy is.
    private PolicyVersionEntity read(final Sql.Row row) throws SQLException {
        final PolicyVersionEntity version = new PolicyVersionEntity();
        version.setId(row.string("id"));
        version.setUserId(row.string("user_id"));
        version.setName(row.string("name"));
        version.setRevision(row.intValue("revision"));
        version.setContentHash(row.string("content_hash"));
        version.setPolicy(SecretFields.open(encryptionService, row.string("policy"), row.string("policy_key")));
        version.setCapturedTimestamp(row.date("captured_timestamp"));
        version.setAuthorId(row.string("author_id"));
        version.setApplied(row.intValue("applied") != 0);
        return version;
    }

    @Override
    protected void insertContent(final String contentHash, final String policyJson) {
        // Shared by every owner whose policy has this content, so it is sealed without one.
        final SecretFields.Sealed sealed = SecretFields.seal(encryptionService, policyJson, null);
        database.write(connection -> Sql.update(connection, "INSERT INTO policy_contents (hash, policy, policy_key) "
                + "VALUES (?, ?, ?)", contentHash, sealed.ciphertext(), sealed.key()));
    }

    @Override
    protected String findContent(final String contentHash) {
        return database.read(connection -> Sql.first(connection, "SELECT policy, policy_key FROM policy_contents WHERE hash = ?",
                row -> SecretFields.open(encryptionService, row.string("policy"), row.string("policy_key")), contentHash));
    }

    @Override
    protected void insertVersion(final PolicyVersionEntity version) {
        final SecretFields.Sealed sealed = SecretFields.seal(encryptionService, version.getPolicy(), version.getUserId());
        database.write(connection -> Sql.update(connection,
                "INSERT INTO policy_versions (id, user_id, name, revision, content_hash, policy, policy_key, captured_timestamp, "
                        + "author_id, applied) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                RecordIds.newId(), version.getUserId(), version.getName(), version.getRevision(), version.getContentHash(),
                sealed == null ? null : sealed.ciphertext(), sealed == null ? null : sealed.key(),
                version.getCapturedTimestamp(), version.getAuthorId(), version.isApplied() ? 1 : 0));
    }

    @Override
    protected PolicyVersionEntity findVersion(final String name, final String userId, final int revision) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policy_versions WHERE " + owner(userId)
                + " AND name = ? AND revision = ?", this::read, ownerParams(userId, name, revision).toArray()));
    }

    @Override
    public void markApplied(final String name, final String userId, final int revision) {
        database.write(connection -> Sql.update(connection, "UPDATE policy_versions SET applied = 1 WHERE " + owner(userId)
                + " AND name = ? AND revision = ? AND applied = 0", ownerParams(userId, name, revision).toArray()));
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
                + " AND name = ? AND applied = 1 ORDER BY revision DESC LIMIT ? OFFSET ?", this::read,
                ownerParams(userId, name, limit, offset).toArray()));
    }

    @Override
    public Listings.Page<PolicyVersionEntity> listByName(final String name, final String userId, final boolean descending,
                                                         final int offset, final int limit) {
        return database.read(connection -> Sql.page(connection, "policy_versions", Sql.where(owner(userId), "name = ?", "applied = 1"),
                ownerParams(userId, name), new Listings.Sort("revision", descending), offset, limit,
                this::read));
    }

    @Override
    public PolicyVersionEntity findByNameAndRevision(final String name, final String userId, final int revision) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM policy_versions WHERE " + owner(userId)
                + " AND name = ? AND revision = ? AND applied = 1", this::read,
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
                "SELECT DISTINCT name FROM policy_versions WHERE user_id = ? AND applied = 1", row -> row.string("name"), userId));
    }

    @Override
    public Deletion findDeletion(final String userId, final String name) {
        return database.read(connection -> Sql.first(connection,
                "SELECT deleted_at, deleted_by FROM policy_deletions WHERE user_id = ? AND name = ?",
                row -> new Deletion(row.date("deleted_at"), row.string("deleted_by")), userId, name));
    }

}
