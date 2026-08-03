# Settings

Philter has settings to control how it operates. The settings and how to configure each are described below.

> The configuration for the types of sensitive information that Philter identifies are defined
> in [filter policies](policies/filter_policies.md) outside of Philter's configuration properties described on this page.

## Configuring Philter

### The Philter Settings File

Philter looks for its settings in a `philter.properties` file in the current directory.

### Using Environment Variables

Properties can also be set via environment variables. Environment variables take precedence over properties set in `philter.properties`.

## Database Settings

Philter requires a MongoDB database to store policies and other data. See [Database](database.md) for what is stored and for connection-string examples (authentication, replica sets, and Atlas).

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MONGODB_CONNECTION_STRING` | The MongoDB connection string. | `mongodb://localhost:27017` |

## Encryption

Philter encrypts sensitive data at rest and requires an encryption key. Philter will not start if the key is missing or invalid.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_ENCRYPTION_KEY` | A base64-encoded 32-byte (AES-256) key. Generate one with `openssl rand -base64 32`. Each record is encrypted with its own random data key, stored wrapped under this key, so this value is required to read any encrypted data. Use the same value across restarts and instances, and back it up: if it is lost or changed, existing encrypted data cannot be recovered and Philter will not start. | (none; required) |

## Cache Settings

The cache is used for API key and context caching. Philter supports Valkey/Redis as the backend cache. The cache is **optional**: when `CACHE_HOSTNAME` is unset or blank, Philter uses an in-memory cache instead. The in-memory cache is ephemeral (it is not shared across instances and is lost on restart) and a warning is printed at startup. Configure Valkey for a durable, shared cache.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `CACHE_HOSTNAME` | The hostname or IP address of the Valkey cache. Leave unset to use an in-memory cache. | (empty; in-memory) |
| `CACHE_PORT` | The Valkey port. | `6379` |
| `CACHE_PASSWORD` | The Valkey password. | (empty) |
| `CACHE_SSL` | Whether to use SSL for communication with the Valkey cache. | `false` |

## Metrics

Philter exposes metrics in Prometheus format at `/actuator/prometheus`. See [Monitoring and Logging](monitoring_and_logging.md). There are no metrics-storage settings to configure.

## API Access

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `API_IP_ALLOWLIST` | Optional comma-separated list of IPv4 addresses/CIDR ranges allowed to call the API. When set, authenticated requests from other addresses receive `403 Forbidden`. A bare address is treated as a single host. IPv4 only. | (empty, allow all) |
| `PHILTER_BOOTSTRAP_API_KEY` | Optional API key to seed at startup so automation and turnkey deployments have a credential without using the dashboard. Must be `sk_` followed by 32 alphanumeric characters (generate one however you provision secrets). When set, it is assigned to the `admin` user, but only if that user has no API keys at all (active or archived), so it is seeded once on a fresh install and never resurrected after you create or revoke a key of your own. Authentication stays enabled. While the bootstrap key is in use, the dashboard shows a warning on login and surfaces the key on the API Keys page. Rotate or revoke it in the dashboard when it is no longer needed. | (empty; UI key creation only) |
| `ADMIN_CROSS_USER_ACCESS_ENABLED` | Whether an administrator may view or act on **other** users' resources — their contexts, policies, custom lists, documents, and redaction ledger — via the API `owner` parameter and the admin "All …" dashboard tabs. **Disabled by default**, so an admin sees only their own data, like any user; set to `true` to opt in. Does not affect ordinary admin functions such as user management. | `false` |
| `LEDGER_DELETION_ENABLED` | Whether [redaction ledger](redaction/ledgers.md) entries may be deleted at all, through `DELETE /api/ledger` or the Redaction Ledgers dashboard. **Disabled by default**: when unset, no ledger evidence can be deleted through Philter and the dashboard controls are hidden. Deletion is additionally restricted to administrators, and [legal holds](redaction/legal_holds.md) still block it. Deleting another user's ledger requires `ADMIN_CROSS_USER_ACCESS_ENABLED` as well. | `false` |

## Dashboard Login

These settings control the dashboard login lockout and session timeout. See [Login Security](login_security.md).

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `LOGIN_MAX_ATTEMPTS` | Number of consecutive failed dashboard logins that triggers a temporary lockout. | `5` |
| `LOGIN_LOCKOUT_SECONDS` | How long a dashboard login lockout lasts, in seconds. | `900` |
| `SESSION_TIMEOUT_MINUTES` | Minutes of inactivity before the dashboard session ends and the user is returned to the login page. | `15` |

Optional multi-factor authentication (TOTP) for the dashboard is enabled in the dashboard **Admin** → **Admin Settings** page, not via an environment variable, and is opt-in per user. See [Multi-factor authentication](login_security.md#multi-factor-authentication-mfa).

## Redaction Ledger

Whether a redaction is recorded in the [redaction ledger](redaction/ledgers.md) is controlled per context by the **Enable the redaction ledger** option set when creating or editing a context. The option is unchecked (disabled) by default, so redactions made in a context are not written to the ledger unless the context has it enabled.

**Ledger entries never expire on their own.** They are governance evidence, so they are removed only by a deliberate deletion: an administrator calling `DELETE /api/ledger` or `DELETE /api/ledger/{documentId}`, or using the equivalent controls in the Redaction Ledgers dashboard. Both require `LEDGER_DELETION_ENABLED=true` (see [API Access](#api-access) above), are refused while a [legal hold](redaction/legal_holds.md) covers the evidence, and are recorded in the [audit log](auditing.md).

To enforce a retention period, schedule the purge endpoint. This gives you time-based retention that is still admin-only, hold-aware, and audited:

```bash
# Daily: delete this account's ledger chains older than 90 days.
curl -X DELETE -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/ledger?older_than_days=90"
```

Earlier builds offered a `REDACTION_LEDGER_TTL_DAYS` variable that created a MongoDB TTL index. It was removed: MongoDB expires documents itself, so that path could not check legal holds and produced no audit record. If a deployment set it, Philter drops the leftover index at startup and logs that it has done so.

## Asynchronous Documents and Webhooks

Records for asynchronous (PDF) redactions and outbound webhook deliveries are expired automatically by MongoDB TTL indexes.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PENDING_DOCUMENTS_TTL_SECONDS` | How long to keep completed asynchronous redaction records (including the input and redacted output bytes) before MongoDB expires them. | `604800` (7 days) |
| `WEBHOOK_DELIVERIES_TTL_SECONDS` | How long to keep delivered webhook records before MongoDB expires them. | `2592000` (30 days) |

## Contexts and Disambiguation

These bound the per-context storage so it does not grow without limit. See [Contexts](redaction/contexts.md).

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MAX_CONTEXT_SIZE` | Maximum number of token-to-replacement entries stored per context. When reached, the least-read entry is evicted. | `10000` |
| `MAX_VECTORS_PER_CONTEXT` | Maximum number of [span disambiguation](other_features/span_disambiguation.md) vectors stored per `(user, context)` pair. When reached, the oldest is evicted (FIFO). | `100000` |

## Redaction Engine

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `INCREMENTAL_REDACTIONS_ENABLED` | Whether Phileas computes incremental redactions. These are required to populate the redaction ledger; leave enabled if any context uses the ledger. | `true` |
| `MAX_FILE_SIZE_BYTES` | Maximum size, in bytes, of an uploaded document or PDF accepted by the filter API. Requests larger than this are rejected. | `10485760` (10 MB) |
| `MAX_FILE_SIZE_BYTES_OTHER` | Maximum size, in bytes, accepted for other (non-document) request bodies. | `10240` (10 KB) |
| `PHEYE_ENDPOINT` | The endpoint of the ph-eye NER service used by policies that perform named-entity recognition. | (none) |

## Output Signing

Philter can sign `POST /api/filter` (text) and `POST /api/explain` responses with an ES256 JWT in the `X-Philter-Signature` response header. Signing is **disabled by default**; enable it in the dashboard **Admin** → **Admin Settings** page. See [Output Signing](output_signing.md) for full documentation.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_SIGNING_KEY_PATH` | Absolute path to a PKCS8 PEM private key file (`BEGIN PRIVATE KEY` format) to use instead of the auto-generated MongoDB key. When set, the file is read on startup; the MongoDB signing key is not used. The file must be accessible on every node. Leave unset to let Philter generate and manage the key automatically. | (none; auto-generate) |

## PII Drift Monitoring (Phield)

Philter can optionally publish per-redaction **PII type counts** to a [Phield](https://github.com/philterd/phield) drift monitor; only counts are sent, never any PII. This is configured in the dashboard **Admin** settings (enable, Phield URL, source id, organization), not via environment variables. See [PII Drift Monitoring with Phield](phield.md).
