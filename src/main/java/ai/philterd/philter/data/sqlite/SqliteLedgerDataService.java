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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredLedgerDataService;
import ai.philterd.philter.data.services.UnreadableLedgerEntryException;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Stores the redaction ledger in SQLite: a row per entry and a row per chain holding its state. */
public class SqliteLedgerDataService extends StoredLedgerDataService {

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteLedgerDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                   final AuditEventPublisher auditEventPublisher,
                                   final LegalHoldDataService legalHoldDataService, final SigningService signingService,
                                   final EvidenceGuard evidenceGuard) {
        super(auditEventPublisher, legalHoldDataService, signingService, evidenceGuard);
        this.database = database;
        this.encryptionService = encryptionService;
    }

    /** The fields stored in the clear. */
    private static LedgerEntity readClear(final Sql.Row row) throws SQLException {
        final LedgerEntity entry = new LedgerEntity();
        entry.setId(row.string("id"));
        entry.setUserId(row.string("user_id"));
        entry.setDocumentId(row.string("document_id"));
        entry.setStartPosition(row.longValue("start_position"));
        entry.setChainPosition(row.longValue("chain_position"));
        entry.setDocumentHash(row.string("document_hash"));
        entry.setPreviousHash(row.string("previous_hash"));
        entry.setHash(row.string("hash"));
        entry.setTimestamp(row.date("timestamp"));
        entry.setFilename(row.string("filename"));
        entry.setType(row.string("type"));
        entry.setPolicyName(row.string("policy_name"));
        entry.setPolicyVersion(row.intValue("policy_version"));
        entry.setPolicyContentHash(row.string("policy_content_hash"));
        entry.setEffectiveHash(row.string("effective_hash"));
        entry.setSignature(row.string("signature"));
        entry.setSigningKeyId(row.string("signing_key_id"));
        return entry;
    }

    private LedgerEntity read(final Sql.Row row) throws SQLException {
        final LedgerEntity entry = readClear(row);
        entry.setToken(SecretFields.open(encryptionService, row.string("token"), row.string("token_encrypted_key")));
        entry.setTokenEncryptedKey(row.string("token_encrypted_key"));
        entry.setReplacement(SecretFields.open(encryptionService, row.string("replacement"), row.string("replacement_encrypted_key")));
        entry.setReplacementEncryptedKey(row.string("replacement_encrypted_key"));
        return entry;
    }

    /** An entry, or, when its token or replacement no longer decrypts, one marked unreadable with its clear fields. */
    private LedgerEntity readOrUnreadable(final Sql.Row row) throws SQLException {
        try {
            return read(row);
        } catch (final RuntimeException unreadable) {
            final LedgerEntity entry = readClear(row);
            entry.markUnreadable(unreadable);
            return entry;
        }
    }

    private static String stored(final ChainState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    @Override
    protected boolean beginWriting(final String userId, final String documentId) {
        return database.write(connection -> {
            Sql.update(connection, "INSERT INTO ledger_chains (user_id, document_id, state) VALUES (?, ?, ?) "
                    + "ON CONFLICT (user_id, document_id) DO NOTHING", userId, documentId, stored(ChainState.OPEN));
            return Sql.update(connection, "UPDATE ledger_chains SET state = ? WHERE user_id = ? AND document_id = ? AND state = ?",
                    stored(ChainState.WRITING), userId, documentId, stored(ChainState.OPEN));
        }) == 1;
    }

    @Override
    protected void endWriting(final String userId, final String documentId, final Date entryTimestamp) {
        // Without a timestamp the latest entry time is left as it is, as MongoDB's $max leaves it.
        database.write(connection -> entryTimestamp == null
                ? Sql.update(connection, "UPDATE ledger_chains SET state = ? WHERE user_id = ? AND document_id = ?",
                        stored(ChainState.OPEN), userId, documentId)
                : Sql.update(connection, "UPDATE ledger_chains SET state = ?, "
                        + "latest_entry_at = max(ifnull(latest_entry_at, 0), ?) WHERE user_id = ? AND document_id = ?",
                        stored(ChainState.OPEN), entryTimestamp.getTime(), userId, documentId));
    }

    @Override
    protected void markWriteFailed(final String userId, final String documentId) {
        database.write(connection -> Sql.update(connection, "UPDATE ledger_chains SET state = ? WHERE user_id = ? AND document_id = ?",
                stored(ChainState.FAILED), userId, documentId));
    }

    @Override
    protected void insertEntry(final LedgerEntity entry) {
        final SecretFields.Sealed token = SecretFields.seal(encryptionService, entry.getToken(), entry.getUserId());
        final SecretFields.Sealed replacement = SecretFields.seal(encryptionService, entry.getReplacement(), entry.getUserId());
        database.write(connection -> Sql.update(connection, "INSERT INTO ledger (id, user_id, document_id, token, "
                + "token_encrypted_key, replacement, replacement_encrypted_key, start_position, document_hash, previous_hash, "
                + "hash, timestamp, filename, type, policy_name, policy_version, policy_content_hash, effective_hash, signature, "
                + "signing_key_id, chain_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                RecordIds.newId(), entry.getUserId(), entry.getDocumentId(), token.ciphertext(), token.key(),
                replacement.ciphertext(), replacement.key(), entry.getStartPosition(), entry.getDocumentHash(),
                entry.getPreviousHash(), entry.getHash(), entry.getTimestamp(), entry.getFilename(), entry.getType(),
                entry.getPolicyName(), entry.getPolicyVersion(), entry.getPolicyContentHash(), entry.getEffectiveHash(),
                entry.getSignature(), entry.getSigningKeyId(), entry.getChainPosition()));
    }

    @Override
    protected boolean complete(final String userId, final String documentId, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE ledger_chains SET state = ?, completed_at = ? "
                + "WHERE user_id = ? AND document_id = ? AND state = ?", stored(ChainState.COMPLETE), now, userId, documentId,
                stored(ChainState.OPEN))) == 1;
    }

    @Override
    protected ChainState chainState(final String userId, final String documentId) {
        final String state = database.read(connection -> Sql.first(connection,
                "SELECT state FROM ledger_chains WHERE user_id = ? AND document_id = ?", row -> row.string("state"),
                userId, documentId));
        return state == null ? null : ChainState.valueOf(state.toUpperCase(Locale.ROOT));
    }

    @Override
    protected boolean markPurged(final String userId, final String documentId) {
        return database.write(connection -> Sql.update(connection, "UPDATE ledger_chains SET state = ? WHERE user_id = ? "
                + "AND document_id = ? AND state IN (?, ?)", stored(ChainState.PURGED), userId, documentId,
                stored(ChainState.COMPLETE), stored(ChainState.PURGED))) == 1;
    }

    @Override
    protected long deleteEntries(final String userId, final String documentId) {
        return database.write(connection -> (long) Sql.update(connection,
                "DELETE FROM ledger WHERE user_id = ? AND document_id = ?", userId, documentId));
    }

    @Override
    protected List<String> findCompletedBefore(final String userId, final Date cutoff) {
        return database.read(connection -> Sql.query(connection, "SELECT document_id FROM ledger_chains WHERE user_id = ? "
                + "AND state = ? AND completed_at < ? AND latest_entry_at < ?", row -> row.string("document_id"),
                userId, stored(ChainState.COMPLETE), cutoff, cutoff));
    }

    @Override
    protected Listings.Page<LedgerEntity> findChainHeads(final String userId, final String q, final Listings.Sort sort,
                                                         final int offset, final int limit) {
        final List<Object> params = new ArrayList<>();
        String owner = null;
        if (userId != null) {
            owner = "user_id = ?";
            params.add(userId);
        }
        params.add(GENESIS);
        String search = null;
        if (q != null) {
            final List<Object> searchParams = new ArrayList<>();
            search = Sql.contains("document_id", q, searchParams) + " OR " + Sql.contains("filename", q, searchParams);
            params.addAll(searchParams);
        }
        final String where = Sql.where(owner, "previous_hash = ?", search);
        return database.read(connection -> Sql.page(connection, "ledger", where, params, sort, offset, limit,
                this::readOrUnreadable));
    }

    @Override
    public boolean chainExists(final String userId, final String documentId) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM ledger WHERE user_id = ? "
                + "AND document_id = ?", userId, documentId) > 0);
    }

    @Override
    public List<LedgerEntity> getChain(final String userId, final String documentId) {
        // In chain order, which the hash links follow; entries written in one millisecond share a timestamp.
        return Collections.unmodifiableList(database.read(connection -> Sql.query(connection,
                "SELECT * FROM ledger WHERE user_id = ? AND document_id = ? ORDER BY chain_position ASC", row -> {
                    try {
                        return read(row);
                    } catch (final RuntimeException ex) {
                        throw new UnreadableLedgerEntryException(documentId, ex);
                    }
                }, userId, documentId)));
    }

    @Override
    public LedgerEntity getLatestTransaction(final String userId, final String documentId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM ledger WHERE user_id = ? AND document_id = ? "
                + "ORDER BY chain_position DESC LIMIT 1", this::read, userId, documentId));
    }

}
