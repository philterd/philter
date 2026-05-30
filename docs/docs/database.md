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
| Redaction ledger | The cryptographic ledger of redactions, when enabled. See [Redaction Ledgers](redaction/ledgers.md). |
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

## Operational notes

* **Backups.** MongoDB holds your policies, contexts, ledger, and audit log. Back it up like any other production datastore.
* **Network access.** Restrict network access to the database to the Philter instances that need it.
* **Multiple Philter instances.** When running more than one Philter instance, point them all at the same MongoDB so they share state. They should also share a cache; see [Caching](caching.md).

## See also

* [Settings](settings.md) for the full list of configuration environment variables.
* [System Requirements](system_requirements.md).
* [Caching](caching.md) for the optional shared cache.
