# Database

Philter requires a [MongoDB](https://www.mongodb.com/) database. MongoDB is the system of record for everything Philter persists; Philter does not start without a reachable database.

## What Philter stores in MongoDB

| Data | Description |
|------|-------------|
| Users | Dashboard user accounts, roles, and (BCrypt-hashed) passwords. |
| API keys | Hashed API keys and their metadata. |
| Policies | Redaction policies, including the managed policies shipped with Philter. |
| Contexts and context entries | Contexts and their token-to-replacement mappings used for referential integrity. |
| Custom lists | Named term lists referenced by policies. |
| Global terms | Per-account always-redact / never-redact term lists. |
| Pending documents | Records for asynchronous PDF redactions, including input and redacted output (subject to a TTL). |
| Webhook deliveries | Delivery records for outbound webhook notifications (subject to a TTL). |
| Redaction ledger | The cryptographic ledger of redactions, when enabled for a context. See [Redaction Ledgers](redaction/ledgers.md). |
| Disambiguation vectors | The per-`(user, context)` vectors learned for [span disambiguation](other_features/span_disambiguation.md), bounded by `MAX_VECTORS_PER_CONTEXT`. |
| Admin settings | Instance-wide administrator settings (for example, whether logging is enabled). |
| Audit events | The audit log of security-relevant actions. See [Auditing](auditing.md). |

Sensitive fields are encrypted at rest before they are written to MongoDB. See [encryption](settings.md#encryption).

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
* `policies`, `contexts`, `custom_lists`, and `global_terms` by user (and name where applicable).
* `context_entries` by `(user_id, context_name, token_hash)` for the redaction hot path.
* `ledger` by chain head and by document.
* `pending_documents` by status and by document, with a TTL index that expires finished records (`PENDING_DOCUMENTS_TTL_SECONDS`, default 7 days).
* `webhook_deliveries` by delivery status, with a TTL index (`WEBHOOK_DELIVERIES_TTL_SECONDS`, default 30 days).
* `users` by `email`, and `audit_events` by `timestamp` and `event`.

If an index cannot be created (for example, the database user lacks permission), Philter logs a warning and continues to start; queries still work but may be slower.

## Operational notes

* **Backups.** MongoDB holds your policies, contexts, ledger, and audit log. Back it up like any other production datastore.
* **Network access.** Restrict network access to the database to the Philter instances that need it.
* **Multiple Philter instances.** When running more than one Philter instance, point them all at the same MongoDB so they share state. They should also share a cache; see [Caching](caching.md).

## See also

* [Settings](settings.md) for the full list of configuration environment variables.
* [System Requirements](system_requirements.md).
* [Caching](caching.md) for the optional shared cache.
