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

import java.util.List;

/**
 * The tables of schema version 1, the layout Philter 4.0 ships with. Times are stored as milliseconds since the
 * epoch, flags as 0 or 1, and ids as the 24-character record ids. Each record table's {@code seq} gives insertion
 * order, which listings break ties by. Later layout changes are migrations, never edits here.
 */
final class SqliteSchema {

    private SqliteSchema() {
    }

    static final List<String> BASELINE = List.of(
            """
            CREATE TABLE admin_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                diffuse_counts_enabled INTEGER NOT NULL DEFAULT 0,
                signing_enabled INTEGER NOT NULL DEFAULT 0,
                webhook_allowlist TEXT NOT NULL DEFAULT '',
                phield_enabled INTEGER NOT NULL DEFAULT 0,
                phield_url TEXT NOT NULL DEFAULT '',
                phield_source_id TEXT NOT NULL DEFAULT 'philter',
                phield_organization TEXT NOT NULL DEFAULT 'philter',
                phield_api_key TEXT,
                phield_api_key_key TEXT,
                mfa_available INTEGER NOT NULL DEFAULT 0,
                mfa_required INTEGER NOT NULL DEFAULT 0)""",
            """
            CREATE TABLE users (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                username TEXT NOT NULL UNIQUE,
                email TEXT,
                role TEXT,
                fpe_key TEXT, fpe_key_key TEXT,
                webhook_url TEXT,
                webhook_secret TEXT, webhook_secret_key TEXT,
                password TEXT,
                password_change_required INTEGER NOT NULL DEFAULT 0,
                mfa_enabled INTEGER NOT NULL DEFAULT 0,
                mfa_secret TEXT, mfa_secret_key TEXT,
                mfa_pending_secret TEXT, mfa_pending_secret_key TEXT,
                mfa_failed_attempts INTEGER NOT NULL DEFAULT 0,
                mfa_locked INTEGER NOT NULL DEFAULT 0,
                mfa_last_used_time_step INTEGER NOT NULL DEFAULT 0,
                sign_in_failures INTEGER NOT NULL DEFAULT 0,
                sign_in_window_until INTEGER,
                sign_in_locked_until INTEGER,
                deactivated INTEGER NOT NULL DEFAULT 0,
                deactivated_at INTEGER,
                created_at INTEGER NOT NULL)""",
            """
            CREATE TABLE api_keys (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                api_key_hash TEXT NOT NULL,
                api_key_prefix TEXT,
                deleted INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER,
                timestamp INTEGER,
                bootstrap INTEGER NOT NULL DEFAULT 0,
                session INTEGER NOT NULL DEFAULT 0,
                expires_at INTEGER,
                idle_timeout_seconds INTEGER NOT NULL DEFAULT 0,
                idle_expires_at INTEGER,
                last_used_at INTEGER,
                password_change_only INTEGER NOT NULL DEFAULT 0,
                mfa_enrollment_only INTEGER NOT NULL DEFAULT 0,
                client_address TEXT,
                user_agent TEXT,
                scopes TEXT NOT NULL DEFAULT '')""",
            "CREATE INDEX api_keys_hash ON api_keys (api_key_hash, deleted)",
            "CREATE INDEX api_keys_user ON api_keys (user_id, deleted, timestamp)",
            "CREATE INDEX api_keys_session ON api_keys (session, deleted)",
            """
            CREATE TABLE sign_in_challenges (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                expires_at INTEGER NOT NULL)""",
            """
            CREATE TABLE signing_keys (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                key_id TEXT NOT NULL UNIQUE,
                private_key BLOB,
                private_key_encrypted_key TEXT,
                public_key BLOB NOT NULL,
                created_at INTEGER)""",
            """
            CREATE TABLE signing_key_state (
                name TEXT PRIMARY KEY,
                key_id TEXT NOT NULL)""",
            """
            CREATE TABLE policies (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT,
                name TEXT NOT NULL,
                policy TEXT,
                policy_key TEXT,
                created_timestamp INTEGER,
                revision INTEGER NOT NULL DEFAULT 0,
                last_updated_timestamp INTEGER,
                shared INTEGER NOT NULL DEFAULT 0,
                notes TEXT,
                notes_key TEXT,
                description TEXT,
                description_key TEXT,
                details_revision INTEGER NOT NULL DEFAULT 0,
                managed INTEGER NOT NULL DEFAULT 0)""",
            "CREATE UNIQUE INDEX policies_name ON policies (ifnull(user_id, ''), name)",
            """
            CREATE TABLE policy_revision_counters (
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                next INTEGER NOT NULL,
                PRIMARY KEY (user_id, name))""",
            """
            CREATE TABLE policy_versions (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT,
                name TEXT NOT NULL,
                revision INTEGER NOT NULL,
                content_hash TEXT,
                policy TEXT,
                policy_key TEXT,
                captured_timestamp INTEGER,
                author_id TEXT)""",
            "CREATE UNIQUE INDEX policy_versions_revision ON policy_versions (ifnull(user_id, ''), name, revision)",
            "CREATE INDEX policy_versions_content ON policy_versions (content_hash)",
            """
            CREATE TABLE policy_contents (
                hash TEXT PRIMARY KEY,
                policy TEXT NOT NULL,
                policy_key TEXT NOT NULL)""",
            """
            CREATE TABLE policy_deletions (
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                revision INTEGER NOT NULL,
                deleted_by TEXT,
                deleted_at INTEGER,
                PRIMARY KEY (user_id, name))""",
            """
            CREATE TABLE custom_lists (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                items TEXT NOT NULL DEFAULT '',
                items_encrypted_key TEXT NOT NULL DEFAULT '',
                UNIQUE (user_id, name))""",
            """
            CREATE TABLE redact_lists (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL UNIQUE,
                terms_to_always_redact TEXT NOT NULL DEFAULT '',
                terms_to_always_redact_key TEXT NOT NULL DEFAULT '',
                terms_to_never_redact TEXT NOT NULL DEFAULT '',
                terms_to_never_redact_key TEXT NOT NULL DEFAULT '',
                always_redact_revision INTEGER NOT NULL DEFAULT 0,
                never_redact_revision INTEGER NOT NULL DEFAULT 0)""",
            """
            CREATE TABLE contexts (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                context_name TEXT NOT NULL,
                max_size INTEGER,
                disambiguation INTEGER NOT NULL DEFAULT 0,
                disambiguation_scope TEXT,
                ledger INTEGER NOT NULL DEFAULT 0,
                slot INTEGER,
                ttl_in_hours INTEGER,
                timestamp INTEGER,
                UNIQUE (user_id, context_name),
                UNIQUE (user_id, slot))""",
            """
            CREATE TABLE context_entries (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                context_name TEXT NOT NULL,
                token_hash TEXT NOT NULL,
                replacement TEXT,
                replacement_uuid INTEGER NOT NULL DEFAULT 0,
                reads INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER,
                filter_type TEXT,
                UNIQUE (user_id, context_name, token_hash))""",
            "CREATE INDEX context_entries_listing ON context_entries (user_id, context_name, timestamp)",
            """
            CREATE TABLE vectors (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                context TEXT NOT NULL,
                hash REAL NOT NULL,
                vector_size INTEGER,
                filter_type TEXT)""",
            "CREATE INDEX vectors_lookup ON vectors (user_id, context, filter_type)",
            """
            CREATE TABLE legal_holds (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                reference TEXT NOT NULL,
                scope_type TEXT NOT NULL,
                scope_value TEXT,
                reason TEXT,
                set_at INTEGER,
                set_by_user_id TEXT,
                UNIQUE (user_id, reference))""",
            """
            CREATE TABLE evidence_guards (
                owner TEXT PRIMARY KEY,
                token TEXT,
                operation TEXT,
                started_at INTEGER)""",
            """
            CREATE TABLE ledger (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                document_id TEXT NOT NULL,
                token TEXT, token_encrypted_key TEXT,
                replacement TEXT, replacement_encrypted_key TEXT,
                start_position INTEGER,
                document_hash TEXT,
                previous_hash TEXT,
                hash TEXT,
                timestamp INTEGER,
                filename TEXT,
                type TEXT,
                policy_name TEXT,
                policy_version INTEGER,
                policy_content_hash TEXT,
                effective_hash TEXT,
                signature TEXT,
                signing_key_id TEXT,
                chain_position INTEGER NOT NULL)""",
            "CREATE UNIQUE INDEX ledger_chain ON ledger (user_id, document_id, chain_position)",
            "CREATE INDEX ledger_heads ON ledger (previous_hash, timestamp)",
            """
            CREATE TABLE ledger_chains (
                user_id TEXT NOT NULL,
                document_id TEXT NOT NULL,
                state TEXT NOT NULL,
                completed_at INTEGER,
                latest_entry_at INTEGER,
                PRIMARY KEY (user_id, document_id))""",
            """
            CREATE TABLE pending_documents (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                document_id TEXT,
                file_name TEXT,
                input_mime_type TEXT,
                output_mime_type TEXT,
                policy_name TEXT,
                policy_version INTEGER,
                policy_content_hash TEXT,
                context_name TEXT,
                client_ip_address TEXT,
                input_size INTEGER NOT NULL DEFAULT 0,
                effective_hash TEXT,
                effective_json TEXT, effective_key TEXT,
                status TEXT,
                error_message TEXT,
                input BLOB, input_encrypted_key TEXT,
                output BLOB, output_encrypted_key TEXT,
                submitted_at INTEGER,
                started_at INTEGER,
                completed_at INTEGER,
                claimed_by TEXT,
                claimed_at INTEGER,
                claim_token TEXT,
                claim_expires_at INTEGER,
                reclaim_count INTEGER NOT NULL DEFAULT 0,
                fairness_round INTEGER NOT NULL DEFAULT 0,
                fairness_key TEXT,
                publication_started_at INTEGER,
                notification_pending INTEGER NOT NULL DEFAULT 0,
                notification_attempted_at INTEGER,
                retention_at INTEGER)""",
            "CREATE INDEX pending_documents_owner ON pending_documents (user_id, document_id)",
            "CREATE INDEX pending_documents_claim ON pending_documents (status, fairness_round, submitted_at)",
            "CREATE INDEX pending_documents_retention ON pending_documents (retention_at)",
            """
            CREATE TABLE queue_admission (
                name TEXT PRIMARY KEY,
                token TEXT,
                started_at INTEGER)""",
            """
            CREATE TABLE queue_scheduling (
                owner TEXT PRIMARY KEY,
                round INTEGER NOT NULL)""",
            """
            CREATE TABLE webhook_deliveries (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                user_id TEXT NOT NULL,
                document_id TEXT,
                event_type TEXT,
                status TEXT,
                url TEXT,
                secret TEXT, secret_encrypted_key TEXT,
                payload TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                claim_token TEXT,
                claim_expires_at INTEGER,
                last_error TEXT,
                next_attempt_at INTEGER,
                created_at INTEGER,
                updated_at INTEGER,
                delivered_at INTEGER,
                completed_at INTEGER)""",
            "CREATE INDEX webhook_deliveries_due ON webhook_deliveries (status, next_attempt_at)",
            "CREATE INDEX webhook_deliveries_user ON webhook_deliveries (user_id, created_at)",
            """
            CREATE TABLE audit_events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL,
                event TEXT,
                request_id TEXT,
                api_key_id TEXT,
                associated_object TEXT,
                client_ip_address TEXT,
                source TEXT,
                details TEXT)""",
            "CREATE INDEX audit_events_time ON audit_events (timestamp, id)",
            """
            CREATE TABLE pii_count_aggregates (
                user_id TEXT NOT NULL,
                context TEXT NOT NULL,
                bucket_start INTEGER NOT NULL,
                total_documents INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER,
                PRIMARY KEY (user_id, context, bucket_start))""",
            """
            CREATE TABLE pii_counts (
                user_id TEXT NOT NULL,
                context TEXT NOT NULL,
                bucket_start INTEGER NOT NULL,
                pii_type TEXT NOT NULL,
                count INTEGER NOT NULL,
                PRIMARY KEY (user_id, context, bucket_start, pii_type))"""
    );

}
