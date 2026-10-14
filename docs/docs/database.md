# Database

Philter stores everything it persists in a [SQLite](#sqlite) file by default, for a single Philter instance with no database server, or in a [MongoDB](https://www.mongodb.com/) database, which several instances can share. Choose with `STORAGE_TYPE` (`sqlite` or `mongodb`); see [Settings](settings.md#database-settings). Philter does not start without its storage. The same data is stored either way: this page describes it with MongoDB's collection names, which SQLite's tables share, and [SQLite](#sqlite) describes how SQLite storage differs.

## Choosing SQLite or MongoDB

Choose the storage with `STORAGE_TYPE` before Philter's first start. A deployment that starts on SQLite can later move its data to MongoDB with the `migrate-storage` command (see [Moving from SQLite to MongoDB](#moving-from-sqlite-to-mongodb)); Philter does not move data from MongoDB to SQLite.

| | SQLite (`STORAGE_TYPE=sqlite`, the default) | MongoDB (`STORAGE_TYPE=mongodb`) |
|---|---|---|
| Philter instances | One. A second Philter on the same file refuses to start. | Any number, sharing one database, for [high availability](high_availability.md). |
| What to run | Nothing: the database is a file Philter creates, at `SQLITE_PATH`. | A MongoDB server, replica set, or managed service such as Atlas, at `MONGODB_CONNECTION_STRING`. |
| Suits | Evaluations, small single-server deployments, edge devices, air-gapped hosts, and the cloud marketplace images. | Production deployments that need more than one instance, or that already run MongoDB. |
| Backups | The database file, copied with SQLite's online backup. | MongoDB's own backup tools. |
| Bundled Compose file | `docker-compose.yml` | `docker-compose.mongodb.yml` |
| [Philter Diffuse](diffuse.md) | Cannot read the PII counts yet; read them through the [PII Counts API](api_and_sdks/api/pii_counts_api.md). | Reads the PII counts from MongoDB. |

Both keep the same data, encrypt the same fields with `PHILTER_ENCRYPTION_KEY`, record a [schema version](#schema-version-and-migrations), and never expire the redaction ledger, audit log, or legal holds on their own. Settings are in [Database Settings](settings.md#database-settings). The sections below describe MongoDB, then [SQLite](#sqlite) describes how SQLite storage differs.

A deployment that sets `MONGODB_CONNECTION_STRING` must also set `STORAGE_TYPE`. Philter refuses to start otherwise, so a deployment that used MongoDB, as Philter 3 always did, does not start on a new, empty SQLite database.

## Supported MongoDB versions

Philter is developed and tested against **MongoDB 8.2**, which is what the bundled `docker-compose.mongodb.yml` pins (`./compose.sh -f docker-compose.mongodb.yml up`). That MongoDB requires authentication and holds the ledger, the audit log and the encrypted PII. Its port is published so `mongosh` and [Philter Diffuse](diffuse.md) can reach it from your machine, and its development password is written in `docker-compose.mongodb.yml`; for real data, use a generated password and do not publish the port. SQLite storage uses the SQLite library built into Philter; there is nothing to install.

## What Philter stores

| Data | Description |
|------|-------------|
| Users | User accounts, roles, webhook settings, and optional bcrypt password hashes. |
| API keys | Hashed API keys and their metadata. |
| Policies | Redaction policies, including the managed policies shipped with Philter. |
| Contexts and context entries | Contexts and their token-to-replacement mappings used for referential integrity. |
| Custom lists | Named term lists referenced by policies. |
| Always/never redact lists | Per-account always-redact / never-redact term lists. |
| Pending documents | Records for asynchronous PDF redactions, including encrypted input/output, attempt tokens, renewable computation leases, and publication reservations. Finished records have a TTL; interrupted publication requires [operator recovery](api_and_sdks/api/documents_api.md#claims-and-recovery). |
| Webhook deliveries | Delivery records for outbound webhook notifications (subject to a TTL). |
| Schema version | The `schema_version` collection: the version of the data layout, when each [migration](#schema-version-and-migrations) ran, and the lock an instance holds while migrating. |
| Evidence operation coordination | The evidence_operation_guards collection serializes hold changes and ledger deletions per owner. Guards do not expire; interrupted operations require the recovery procedure in [Legal Holds](redaction/legal_holds.md#concurrent-operations-and-recovery). |
| Administrator change coordination | The `administrator_change_guard` collection lets one change at a time, across instances, demote or deactivate an administrator, so concurrent changes cannot remove the last active administrator. A guard left by an instance that stopped is free again after 30 seconds. |
| Active signing key | The signing_key_state collection contains the single active-key pointer. Key history remains in signing_keys so earlier signatures can be verified after rotation. |
| Ledger chain lifecycle | The `ledger_chains` collection tracks open, writing, failed, completed, and purged chains. Purged markers prevent late appends; they contain owner/document IDs and lifecycle timestamps, not tokens or replacements. |
| Redaction ledger | The cryptographic ledger of redactions, when enabled for a context. See [Redaction Ledgers](redaction/ledgers.md). |
| Disambiguation vectors | The `vectors` collection: per-`(user, context)` vector hashes learned for [span disambiguation](other_features/span_disambiguation.md), bounded by `MAX_VECTORS_PER_CONTEXT`. |
| Sign-in challenges | The `sign_in_challenges` collection: short-lived, single-use MFA sign-in challenges, stored as a hash and expired by a TTL index. |
| Admin settings | Instance-wide administrator settings, such as output signing and Phield publishing. See the [Settings API](api_and_sdks/api/settings_api.md). |
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
| `pending_documents` | The submitted document, the redacted result, and the resolved configuration captured for the job |
| `signing_keys` | The private half of the signing keypair (the public half stays readable, since verifiers need it) |
| `admin_settings` | The Phield API key |

The audit log is deliberately readable: it is evidence of who did what, and encrypting it would make
it unusable for the reporting it exists for. It records event names, the acting API key, an object
id, a client address and a short detail string, not document content.

**Not encrypted:** API keys (stored as a hash), context entries (stored as a token hash, not the
original value), policies and their version snapshots, contexts, legal holds, disambiguation vectors (`vectors`, hashes only), sign-in challenges (stored as a hash), the rest of the admin
settings, webhook delivery metadata and payloads, a pending document's submitting client address (recorded on its
audit events, which hold it in the clear too), and the audit log. These hold no recoverable secret, with one
exception you control.

> **A policy can hold a secret, and then it is stored in the clear.** The `crypto` and `fpe` sections
> of a policy may contain an encryption key. Policies and their version snapshots are not encrypted,
> and the values those keys encrypted are held in `context_entries`, which is not encrypted either,
> so a key written into a policy can be read by anyone who can read the database, along with
> everything it encrypted. Prefix the value with `env:` (for example `env:CRYPTO_KEY`) to keep the key
> in the environment and store only the variable's name; the variable must be listed in
> [`POLICY_ALLOWED_ENV_KEYS`](settings.md#environment-variables-in-a-policy). Philter logs a warning when a policy is saved
> with a key in it. Note that a version snapshot keeps whatever the policy held at the time, so
> changing an existing policy to use `env:` does not remove the old key from its history.

## Configuring the MongoDB connection

With `STORAGE_TYPE=mongodb`, Philter connects to MongoDB using a standard [MongoDB connection string](https://www.mongodb.com/docs/manual/reference/connection-string/), set with the `MONGODB_CONNECTION_STRING` environment variable.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MONGODB_CONNECTION_STRING` | The MongoDB connection string Philter uses to connect to the database. | `mongodb://localhost:27017` |

Philter always uses the database named `philter`. A database name in the connection string does not change it; there it only serves as the default authentication database when `authSource` is not set.

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

> Philter launched from a cloud marketplace image uses SQLite storage, with its database on the virtual machine's disk, and runs no MongoDB. To have it use MongoDB instead, for example an external or managed database, set `STORAGE_TYPE=mongodb` and `MONGODB_CONNECTION_STRING` before putting data in it.

## MongoDB indexes

With MongoDB, Philter creates the indexes it needs automatically at startup. Each data service ensures its own indexes when it initializes, and index creation in MongoDB is idempotent, so this is safe on every restart and adds no manual setup. The indexes cover the access patterns Philter uses, for example:

* `api_keys` by `api_key_hash` (the authentication lookup) and by user.
* `policies`, `contexts`, `custom_lists`, and `redact_lists` by user (and name where applicable).
* `context_entries` by `(user_id, context_name, token_hash)` for the redaction hot path.
* `ledger` by chain head and by document for each user, and by chain head across users.
* `pending_documents` by status and by document, with a TTL index that expires finished records (`PENDING_DOCUMENTS_TTL_SECONDS`, default 7 days).
* `webhook_deliveries` by delivery status, with a TTL index (`WEBHOOK_DELIVERIES_TTL_SECONDS`, default 30 days).
* `users` by `username`, and `audit_events` by `timestamp`, by `timestamp` and `_id` (for paging the CSV export), and by `event`.

Required uniqueness and retention indexes must be created and their options verified; a failure prevents startup. Incompatible TTL settings also fail startup. Only optional performance-index failures are logged and tolerated. Ledger collections must have no automatic TTL index, regardless of its name. Philter rejects incompatible indexes without dropping them. Provision the required schema and privileges before starting the application.

## Schema version and migrations

Philter records the version of its data layout in `schema_version` in its database, a collection with MongoDB and a table with SQLite, and checks it each time it starts, before it serves any request:

* **A new database** is recorded at the version the running Philter expects.
* **An older version** is brought up to date: Philter runs each pending migration in order and records each one, with when it ran, as it completes. A database that holds data but no recorded version was written before versions were recorded and is migrated from the first version.
* **A newer version**, written by a newer Philter, stops Philter from starting with a message naming both versions. Philter does not change the data. Run the newer release, or restore a backup taken before it ran.
* **A failed migration** stops Philter from starting. The log names the migration and why it failed, and the recorded version stays at the last migration that completed, so a release with a fix continues from there.

When several instances start together, as they can with MongoDB, one takes a lock and migrates while the others wait for it, up to `SCHEMA_MIGRATION_WAIT_SECONDS` (see [Settings](settings.md#database-settings)). The lock expires if the instance holding it stops, so another can take over. Each start logs the version it found and each migration it ran.

Migrations change data in place. Back up the database before upgrading Philter; see [Upgrading](upgrading.md#upgrading-within-4x).

## MongoDB operational notes

* **Backups.** MongoDB holds your policies, contexts, ledger, and audit log. Back it up like any other production datastore, and before every upgrade, since an upgrade may [migrate the data](#schema-version-and-migrations).
* **Network access.** Restrict network access to the database to the Philter instances that need it.
* **Multiple Philter instances.** When running more than one Philter instance, point them all at the same MongoDB so they share state. They should also share a cache; see [Caching](caching.md).

## SQLite

SQLite storage, the default (`STORAGE_TYPE=sqlite`), keeps all of Philter's data in one SQLite file, named by `SQLITE_PATH`, with no database server to run. It suits evaluations, small single-server deployments, edge devices, and air-gapped hosts. Everything above about what is stored and what is encrypted at rest applies to it too: the same data is kept, the same fields are encrypted with the same `PHILTER_ENCRYPTION_KEY`, and the redaction ledger, audit log, and legal holds never expire on their own.

**One Philter instance only.** SQLite storage supports exactly one Philter process per database file. While it runs, Philter holds an exclusive lock on a file beside the database (`SQLITE_PATH` with `.lock` appended), and a second Philter started against the same database refuses to start. Several instances behind a load balancer, as in [High Availability](high_availability.md), need MongoDB. Do not put the database on a network file system: SQLite's locking is not reliable there.

**The files.** Philter creates the database file at first start, along with SQLite's write-ahead log files beside it (`-wal` and `-shm`). Keep them together, on local disk, in a directory only Philter's user can read. Philter creates these files readable and writable only by the user it runs as, and narrows an existing database file that other users could read when it starts. The Docker image's data directory is readable only by its `philter` user. The Docker image keeps the database at `/opt/philter/data/philter.db`; mount a volume at `/opt/philter/data`, as the bundled `docker-compose.yml` does, or the data is lost when the container is removed.

**Schema.** At first start Philter creates the tables of the current [schema version](#schema-version-and-migrations), and later releases migrate them at startup as they do for MongoDB.

**Locks left by a stop.** If Philter stops part way through an asynchronous submission or a legal-hold or ledger-deletion operation, the lock that operation held stays held. With SQLite, Philter clears such locks when it next starts, since no other Philter can be using the database, and records each one in the audit log as `storage_guard_recovered`. See [Legal Holds](redaction/legal_holds.md#concurrent-operations-and-recovery).

**Expiry.** Instead of TTL indexes, finished asynchronous documents, finished webhook deliveries, and sign-in challenges are deleted once they are past their retention, by a check that runs every minute and as Philter writes new ones. An expired sign-in challenge or session key is refused whether or not it has been deleted yet.

**Backups.** SQLite's online backup is consistent while Philter runs. Run it with the `sqlite3` command line tool on the host that holds the database, as the user Philter runs as or as root, for example `sqlite3 /srv/philter/data/philter.db ".backup '/backups/philter.db'"` where `/srv/philter/data` is the directory mounted at `/opt/philter/data`. The Docker image does not include `sqlite3`. Otherwise, stop Philter and copy the database file together with its `-wal` and `-shm` files; copying the database file alone while Philter runs can miss writes still in the `-wal` file. Back up `PHILTER_ENCRYPTION_KEY` separately: the encrypted data in a backup cannot be read without it.

A backup holds everything the database does, including password hashes and the audit log, and a copy made with `sqlite3` or `cp` gets the permissions your umask gives new files, not the database's. Keep backups where only the people who administer Philter can read them, for example with `umask 077` before making the copy, or `chmod 600` on the backup file and `chmod 700` on its directory.

**Changing storage.** A deployment that outgrows one instance can move its SQLite database to MongoDB; see [Moving from SQLite to MongoDB](#moving-from-sqlite-to-mongodb). Philter does not move data from MongoDB to SQLite. A deployment that sets `MONGODB_CONNECTION_STRING` must also set `STORAGE_TYPE`; Philter refuses to start otherwise, so a deployment that used MongoDB does not start on a new, empty SQLite database.

## Moving from SQLite to MongoDB

A deployment that starts on SQLite and later needs several Philter instances behind a load balancer, as in [High Availability](high_availability.md), moves its data to MongoDB with the `migrate-storage` command. It copies everything SQLite storage holds into an empty MongoDB database: users with their password hashes and MFA, API keys, policies with their version history, custom and redact lists, contexts with their mappings, legal holds, the redaction ledger, the signing keys, asynchronous documents and webhook deliveries, PII counts, and the audit log.

Records are copied as stored. Every record keeps its id, timestamps, revisions, and state; ledger entries keep their hashes and signatures, so chains signed before the move still verify after it; encrypted fields are copied without being decrypted, so the same `PHILTER_ENCRYPTION_KEY` reads them on MongoDB. Nothing is re-audited or re-signed.

1. **Stop Philter.** The command refuses to run while a Philter is using the SQLite database.
2. **Back up the SQLite database** (see [Backups](#sqlite)). The command opens it read-only and does not change it, but keep the backup until Philter is running on MongoDB.
3. **Run the command** with the SQLite database, an empty MongoDB database, and the encryption key:

    ```
    SQLITE_PATH=/opt/philter/data/philter.db \
    MONGODB_CONNECTION_STRING=mongodb://... \
    PHILTER_ENCRYPTION_KEY=... \
    java -jar philter.jar migrate-storage --to mongodb
    ```

    With Docker, run the image with the data volume mounted, the same variables, and the command in place of the image's own (Docker Compose prefixes the bundled `docker-compose.yml`'s `philter-data` volume with the project name; `docker volume ls` shows the full name):

    ```
    docker run --rm -v philter-data:/opt/philter/data \
        -e MONGODB_CONNECTION_STRING=mongodb://... -e PHILTER_ENCRYPTION_KEY=... \
        philterd/philter java -jar /opt/philter/philter.jar migrate-storage --to mongodb
    ```
4. **Start Philter** with `STORAGE_TYPE=mongodb`, the same `MONGODB_CONNECTION_STRING`, and the same `PHILTER_ENCRYPTION_KEY`. Keep the SQLite database until you have confirmed Philter works on MongoDB; going back means starting Philter on it again with `STORAGE_TYPE=sqlite`.

The command refuses, writing nothing to MongoDB, when the MongoDB database already holds Philter data (it never merges), when the SQLite database is not at the schema version this Philter expects (start this release on it once, stop it, and migrate), when a Philter is using the SQLite database, or when a Philter stopped part way through an operation and left a lock held in it (start Philter on it once with `STORAGE_TYPE=sqlite`, which clears the lock and records that in the audit log, stop it, and migrate).

After copying, it checks the copy: the number of records of each kind, every ledger chain's hashes and signatures, the active signing key and every retained public key, and a sample of each kind of encrypted field, decrypted with `PHILTER_ENCRYPTION_KEY`. It prints how many records of each kind it copied, and exits with status 0 only when every check passes. When a check fails, it says what did not match and exits with status 1; do not start Philter on that MongoDB database. Drop its `philter` database, fix the cause (most often a different `PHILTER_ENCRYPTION_KEY`, or a ledger chain that did not validate in SQLite either), and run the command again. A successful migration is recorded in the audit log on MongoDB as `storage_migrated`, with the source file and the counts copied.

Not copied: the schema version history, which MongoDB records afresh at the version copied, and locks held by operations in progress, which hold nothing once Philter has stopped.

## See also

* [Settings](settings.md) for the full list of configuration environment variables.
* [System Requirements](system_requirements.md).
* [Caching](caching.md) for the optional shared cache.

Policy JSON is retained in `policy_contents` by SHA-256, independently of immutable owner/name/revision references in `policy_versions`. `policy_revision_counters` retains revision allocation across live policy deletion and name reuse. Failed or competing attempts can leave revision gaps and retained candidate snapshots; the live policy identifies the published head. Explicit queued policy pins fail if their content is missing or does not match its fingerprint.

## Async coordination and retention

`pending_documents` uses `retention_at` for TTL, set only after durable notification intent is acknowledged. `webhook_deliveries` uses terminal `completed_at` for both success and failure. Unexpected TTL fields prevent startup. A job's resolved configuration is stored only on its `pending_documents` record and expires or is deleted with it. `queue_admission` serializes capacity checks and insertion across instances; ambiguous operations retain the guard for [operator recovery](settings.md#async-queue-admission). `queue_scheduling` retains per-account/global scheduling rounds for fair claims. A job's optional `fairness_key` orders an account's own jobs among its rounds and is stored unencrypted. No legacy migration or backfill is performed.
