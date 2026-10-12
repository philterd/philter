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
import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredSigningKeyDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.util.Date;

/** Stores signing keys in SQLite, with the active key named in a pointer row. */
public class SqliteSigningKeyDataService extends StoredSigningKeyDataService {

    /** The encryption owner the signing keys are encrypted for: they belong to Philter, not a user. */
    private static final String SYSTEM_OWNER = "system";

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteSigningKeyDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                       final AuditEventPublisher auditEventPublisher) {
        this(database, encryptionService, auditEventPublisher, System.getenv("PHILTER_SIGNING_KEY_PATH"));
    }

    SqliteSigningKeyDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                final AuditEventPublisher auditEventPublisher, final String keyPath) {
        super(auditEventPublisher, keyPath);
        this.database = database;
        this.encryptionService = encryptionService;
        start();
    }

    @Override
    protected void insertKey(final SigningKeyEntity key) {
        final SecretFields.SealedBytes sealed = SecretFields.sealBytes(encryptionService, key.getPrivateKeyEncoded(), SYSTEM_OWNER);
        database.write(connection -> Sql.update(connection,
                "INSERT INTO signing_keys (key_id, private_key, private_key_encrypted_key, public_key, created_at) VALUES (?, ?, ?, ?, ?)",
                key.getKeyId(), sealed.ciphertext(), sealed.key(), key.getPublicKeyEncoded(), key.getCreatedAt()));
    }

    @Override
    protected void retainPublicKey(final String keyId, final byte[] publicKeyEncoded) {
        database.write(connection -> Sql.update(connection,
                "INSERT INTO signing_keys (key_id, public_key, created_at) VALUES (?, ?, ?) ON CONFLICT (key_id) DO NOTHING",
                keyId, publicKeyEncoded, new Date()));
    }

    @Override
    protected SigningKeyEntity findKey(final String keyId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM signing_keys WHERE key_id = ?", row -> {
            final SigningKeyEntity key = new SigningKeyEntity();
            key.setKeyId(row.string("key_id"));
            final String wrapped = row.string("private_key_encrypted_key");
            if (wrapped == null || wrapped.isBlank()) {
                throw new IllegalStateException("Signing private key is missing its encryption key.");
            }
            key.setPrivateKeyEncoded(SecretFields.openBytes(encryptionService, row.bytes("private_key"), wrapped));
            key.setPublicKeyEncoded(row.bytes("public_key"));
            key.setCreatedAt(row.date("created_at"));
            return key;
        }, keyId));
    }

    @Override
    protected byte[] findPublicKey(final String keyId) {
        return database.read(connection -> Sql.first(connection, "SELECT public_key FROM signing_keys WHERE key_id = ?",
                row -> row.bytes("public_key"), keyId));
    }

    @Override
    protected String findActiveKeyId() {
        return database.read(connection -> Sql.first(connection, "SELECT key_id FROM signing_key_state WHERE name = 'active'",
                row -> row.string("key_id")));
    }

    @Override
    protected boolean publishFirstActiveKey(final String keyId) {
        return database.write(connection -> Sql.update(connection,
                "INSERT INTO signing_key_state (name, key_id) VALUES ('active', ?) ON CONFLICT (name) DO NOTHING", keyId)) == 1;
    }

    @Override
    protected boolean publishActiveKey(final String keyId) {
        return database.write(connection -> Sql.update(connection,
                "UPDATE signing_key_state SET key_id = ? WHERE name = 'active'", keyId)) == 1;
    }

    @Override
    protected boolean isActiveAdministrator(final String userId) {
        return SqliteUsers.isActiveAdministrator(database, userId);
    }

}
