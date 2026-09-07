# Database

Philter requires a [MongoDB](https://www.mongodb.com/) database. MongoDB is the system of record for everything Philter persists; Philter does not start without a reachable database.

## Supported versions

Philter is developed and tested against **MongoDB 8.2**, which is what the bundled `docker-compose.yml` pins. That MongoDB requires authentication and is not published to the host: it holds the ledger, the audit log and the encrypted PII, and Philter reaches it over the compose network. `compose.sh` generates the password into `.env` on first run. To attach `mongosh` or [Philter Diffuse](diffuse.md) from your machine, uncomment the `ports` block on the `mongodb` service.

## What Philter stores in MongoDB

| Data | Description |
|------|-------------|
| Users | Dashboard user accounts, roles, and (BCrypt-hashed) passwords. |
| API keys | Hashed API keys and their metadata. |
| Policies | Redaction policies, including the managed policies shipped with Philter. |
| Contexts and context entries | Contexts and their token-to-replacement mappings used for referential integrity. |
| Custom lists | Named term lists referenced by policies. |
| Always/never redact lists | Per-account always-redact / never-redact term lists. |
| Pending documents | Records for asynchronous PDF redactions, including encrypted input/output, attempt tokens, renewable computation leases, and publication reservations. Finished records have a TTL; interrupted publication requires [operator recovery](api_and_sdks/api/documents_api.md#claims-and-recovery). |
| Webhook deliveries | Delivery records for outbound webhook notifications (subject to a TTL). |
| Evidence operation coordination | The evidence_operation_guards collection serializes hold changes and ledger deletions per owner. Guards do not expire; interrupted operations require the recovery procedure in [Legal Holds](redaction/legal_holds.md#concurrent-operations-and-recovery). |
| Active signing key | The signing_key_state collection contains the single active-key pointer. Key history remains in signing_keys so earlier signatures can be verified after rotation. |
| Ledger chain lifecycle | The `ledger_chains` collection tracks open, writing, failed, completed, and purged chains. Purged markers prevent late appends; they contain owner/document IDs and lifecycle timestamps, not tokens or replacements. |
| Redaction ledger | The cryptographic ledger of redactions, when enabled for a context. See [Redaction Ledgers](redaction/ledgers.md). |
| Disambiguation vectors | The per-`(user, context)` vectors learned for [span disambiguation](other_features/span_disambiguation.md), bounded by `MAX_VECTORS_PER_CONTEXT`. |
| Admin settings | Instance-wide administrator settings (for example, whether logging is enabled). |
| Audit events | The audit log of security-relevant actions. See [Auditing](auditing.md). |

Some collections are encrypted at rest and some are not. See [What is encrypted at rest](#what-is-encrypted-at-rest) below and [encryption](settings.md#encryption).

## What is encrypted at rest

Encryption is per collection, not blanket. Each encrypted record carries its own random data key,
stored wrapped under `PHILTER_ENCRYPTION_KEY`, so the master key is required to read any of it.

**Encrypted:**

| Collection | What is protected |
|---|---|
| `users` | Account details and the webhook secret |
| `ledger` | The original token and its replacement |
| `custom_lists` | List items |
| `redact_lists` | Always/never redact terms |
| `webhook_deliveries` | The queued webhook signing secret; delivery metadata and payload remain readable |
| `pending_documents` | The submitted document and the redacted result |
| `signing_keys` | The private half of the signing keypair (the public half stays readable, since verifiers need it) |
| `admin_settings` | The Phield API key |

The audit log is deliberately readable: it is evidence of who did what, and encrypting it would make
it unusable for the reporting it exists for. It records event names, the acting API key, an object
id, a client address and a short detail string — not document content.

**Not encrypted:** API keys (stored as a hash), context entries (stored as a token hash, not the
original value), policies and their version snapshots, contexts, legal holds, the rest of the admin
settings, webhook delivery metadata and payloads, and the audit log. These hold no recoverable secret — with one
exception you control.

> **A policy can hold a secret, and then it is stored in the clear.** The `crypto` and `fpe` sections
> of a policy may contain an encryption key. Policies and their version snapshots are not encrypted,
> and the values those keys encrypted are held in `context_entries`, which is not encrypted either —
> so a key written into a policy can be read by anyone who can read the database, along with
> everything it encrypted. Prefix the value with `env:` (for example `env:CRYPTO_KEY`) to keep the key
> in the environment and store only the variable's name. Philter logs a warning when a policy is saved
> with a key in it. Note that a version snapshot keeps whatever the policy held at the time, so
> changing an existing policy to use `env:` does not remove the old key from its history.

## Configuring the connection

Philter connects to MongoDB using a standard [MongoDB connection string](https://www.mongodb.com/docs/manual/reference/connection-string/), set with the `MONGODB_CONNECTION_STRING` environment variable.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MONGODB_CONNECTION_STRING` | The MongoDB connection string Philter uses to connect to the database. | `mongodb://localhost:27017` |

The connection string also selects the database name. Philter uses the `philter` database.

### Examples

A local, unauthenticated MongoDB (the default):

```
MONGODB_CONNECTION_STRING=mongodb://localhost:27017
```

A MongoDB that requires authentication:

```
MONGODB_CONNECTION_STRING=mongodb://philter_user:your-password@db-host:27017/philter?authSource=admin
```

A replica set (recommended for production, so the database is not a single point of failure):

```
MONGODB_CONNECTION_STRING=mongodb://user:pass@host1:27017,host2:27017,host3:27017/philter?replicaSet=rs0&authSource=admin
```

[MongoDB Atlas](https://www.mongodb.com/atlas) or another `mongodb+srv` host:

```
MONGODB_CONNECTION_STRING=mongodb+srv://user:pass@cluster0.example.mongodb.net/philter?retryWrites=true&w=majority
```

> When Philter is launched from a cloud marketplace image, a local MongoDB is already installed and configured, and no connection string needs to be set. Set `MONGODB_CONNECTION_STRING` only when you want Philter to use a different (for example, external or managed) database.

## Indexes

Philter creates the indexes it needs automatically at startup. Each data service ensures its own indexes when it initializes, and index creation in MongoDB is idempotent, so this is safe on every restart and adds no manual setup. The indexes cover the access patterns Philter uses, for example:

* `api_keys` by `api_key_hash` (the authentication lookup) and by user.
* `policies`, `contexts`, `custom_lists`, and `redact_lists` by user (and name where applicable).
* `context_entries` by `(user_id, context_name, token_hash)` for the redaction hot path.
* `ledger` by chain head and by document.
* `pending_documents` by status and by document, with a TTL index that expires finished records (`PENDING_DOCUMENTS_TTL_SECONDS`, default 7 days).
* `webhook_deliveries` by delivery status, with a TTL index (`WEBHOOK_DELIVERIES_TTL_SECONDS`, default 30 days).
* `users` by `email`, and `audit_events` by `timestamp` and `event`.

Required uniqueness and retention indexes must be created and their options verified; a failure prevents startup. Incompatible TTL settings also fail startup. Only optional performance-index failures are logged and tolerated. Ledger collections must have no automatic TTL index, regardless of its name. Philter rejects incompatible schemas without dropping indexes or migrating data. Provision the required schema and privileges before starting the application.

## Operational notes

* **Backups.** MongoDB holds your policies, contexts, ledger, and audit log. Back it up like any other production datastore.
* **Network access.** Restrict network access to the database to the Philter instances that need it.
* **Multiple Philter instances.** When running more than one Philter instance, point them all at the same MongoDB so they share state. They should also share a cache; see [Caching](caching.md).

## See also

* [Settings](settings.md) for the full list of configuration environment variables.
* [System Requirements](system_requirements.md).
* [Caching](caching.md) for the optional shared cache.

Policy JSON is retained in `policy_contents` by SHA-256, independently of immutable owner/name/revision references in `policy_versions`. `policy_revision_counters` retains revision allocation across live policy deletion and name reuse. Failed or competing attempts can leave revision gaps and retained candidate snapshots; the live policy identifies the published head. Explicit queued policy pins fail if their content is missing or does not match its fingerprint.

## Async coordination and retention

`pending_documents` uses `retention_at` for TTL, set only after durable notification intent is acknowledged. `webhook_deliveries` uses terminal `completed_at` for both success and failure. Unexpected TTL fields prevent startup. `execution_snapshots` retains account-encrypted resolved configurations by owner and content hash independently of job retention; snapshots have no automatic TTL. `queue_admission` serializes capacity checks and insertion across instances; ambiguous operations retain the guard for [operator recovery](settings.md#async-queue-admission). `queue_scheduling` retains per-account/global scheduling rounds for fair claims. No legacy migration or backfill is performed.
