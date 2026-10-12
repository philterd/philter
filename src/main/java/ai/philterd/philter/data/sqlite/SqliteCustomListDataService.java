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
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredCustomListDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores custom lists in SQLite, one row per list. */
public class SqliteCustomListDataService extends StoredCustomListDataService {

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteCustomListDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                       final AuditEventPublisher auditEventPublisher) {
        super(auditEventPublisher);
        this.database = database;
        this.encryptionService = encryptionService;
    }

    private CustomListEntity read(final Sql.Row row) throws SQLException {
        final CustomListEntity list = new CustomListEntity();
        list.setId(row.string("id"));
        list.setUserId(row.string("user_id"));
        list.setName(row.string("name"));
        list.setDescription(row.string("description"));
        list.setItems(SecretFields.openLines(encryptionService, row.string("items"), row.string("items_encrypted_key")));
        list.setItemsEncryptedKey(row.string("items_encrypted_key"));
        return list;
    }

    @Override
    protected String insert(final CustomListEntity list) {
        final String id = RecordIds.newId();
        final SecretFields.Sealed items = SecretFields.sealLines(encryptionService, list.getItems(), list.getUserId());
        database.write(connection -> Sql.update(connection,
                "INSERT INTO custom_lists (id, user_id, name, description, items, items_encrypted_key) VALUES (?, ?, ?, ?, ?, ?)",
                id, list.getUserId(), list.getName(), list.getDescription(), items.ciphertext(), items.key()));
        return id;
    }

    @Override
    protected void update(final CustomListEntity list) {
        final SecretFields.Sealed items = SecretFields.sealLines(encryptionService, list.getItems(), list.getUserId());
        database.write(connection -> Sql.update(connection,
                "UPDATE custom_lists SET name = ?, description = ?, items = ?, items_encrypted_key = ? WHERE id = ?",
                list.getName(), list.getDescription(), items.ciphertext(), items.key(), list.getId()));
    }

    @Override
    public Map<String, List<String>> findItemsByNames(final String userId, final Collection<String> names) {
        if (userId == null || names == null || names.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, List<String>> itemsByName = new HashMap<>();
        for (final String name : names) {
            final CustomListEntity list = findOneByName(name, userId);
            if (list != null) {
                itemsByName.put(list.getName(), list.getItems());
            }
        }
        return itemsByName;
    }

    @Override
    public CustomListEntity findOneByName(final String name, final String userId) {
        return database.read(connection -> Sql.first(connection,
                "SELECT * FROM custom_lists WHERE user_id = ? AND name = ?", this::read, userId, name));
    }

    @Override
    public Listings.Page<CustomListEntity> list(final String userId, final String q, final Listings.Sort sort,
                                                final int offset, final int limit) {
        final List<Object> params = new ArrayList<>();
        String owner = null;
        if (userId != null) {
            owner = "user_id = ?";
            params.add(userId);
        }
        final String where = Sql.where(owner, Sql.contains("name", q, params));
        return database.read(connection -> Sql.page(connection, "custom_lists", where, params, sort, offset, limit, this::read));
    }

    @Override
    public void deleteByName(final String name, final String userId) {
        database.write(connection -> Sql.update(connection, "DELETE FROM custom_lists WHERE user_id = ? AND name = ?", userId, name));
    }

}
