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

Philter encrypts sensitive data at rest and requires an encryption key. Encryption is applied per collection; see [what is encrypted](database.md#what-is-encrypted-at-rest). Philter will not start if the key is missing or invalid. The `compose.sh` script in the repository generates one into a `.env` file on first run and reuses it after that, which is the simplest way to keep the key stable across restarts. It generates the bootstrap API key and the MongoDB password into the same file.

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
| `SCHEDULER_POOL_SIZE` | Threads available to Philter's background workers: one redacts asynchronous documents, the other delivers webhooks. With a single thread they block each other, so a slow document delays delivery. Raise it only if you add further scheduled work. | `2` |
| `ADMIN_SETTINGS_CACHE_TTL_SECONDS` | How long an instance caches the admin settings (output signing, Phield, Diffuse) before re-reading them. They are read on every redaction, so caching keeps that off the database. A change made through this instance's dashboard applies immediately; one made on another instance is picked up within this window. | `60` |

## Metrics

Philter exposes metrics in Prometheus format at `/actuator/prometheus`. See [Monitoring and Logging](monitoring_and_logging.md). There are no metrics-storage settings to configure.

## TLS

Philter serves HTTPS on port 8080. The Docker image generates a self-signed certificate the first time it starts and writes it to `/opt/philter/ssl/philter.p12`. That certificate is not signed by a certificate authority, so clients must skip verification (`curl -k`) and browsers warn before showing the dashboard. It encrypts the connection but does not prove Philter's identity, which is why every example in this documentation passes `-k`.

Replace it for anything beyond evaluation. There are two ways to do that:

**Supply your own certificate.** Put it in a PKCS12 keystore, mount the keystore into the container, and point `SSL_KEYSTORE` at it. Nothing is generated when a keystore already exists at that path.

```yaml
services:
  philter:
    environment:
      SSL_KEYSTORE: /opt/philter/ssl/philter-cert.p12
      SSL_KEYSTORE_PASSWORD: <keystore password>
    volumes:
      - ./philter-cert.p12:/opt/philter/ssl/philter-cert.p12:ro
```

**Terminate TLS in front of Philter**, at a load balancer or ingress, and set `SSL_ENABLED=false` so Philter serves plain HTTP on the same port. This is the usual arrangement for a [multi-node deployment](high_availability.md), where the load balancer already holds the certificate. Keep the network between the load balancer and Philter private: the redaction API carries the sensitive text you are redacting.

Running the JAR directly serves plain HTTP unless you set `SSL_ENABLED=true`, since no certificate is generated outside the container.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `SSL_ENABLED` | Whether Philter serves HTTPS. Set to `false` when TLS is terminated in front of Philter. The port is 8080 either way. | `true` in the Docker image, `false` when running the JAR |
| `SSL_KEYSTORE` | Path to the keystore holding the certificate and private key. When the container starts and no file exists at this path, a self-signed certificate is generated there. | `/opt/philter/ssl/philter.p12` |
| `SSL_KEYSTORE_PASSWORD` | The keystore password. | `philter` |
| `SSL_KEYSTORE_TYPE` | The keystore format, `PKCS12` or `JKS`. | `PKCS12` |
| `SSL_KEY_ALIAS` | The alias of the key to use within the keystore. | `philter` |
| `SSL_CERTIFICATE_HOSTNAME` | The hostname recorded in the generated certificate's subject and subject alternative name. Used only when generating one. | `localhost` |

### Outbound TLS

The settings above govern the certificate Philter *serves*. This one governs how Philter treats certificates it *receives* when the redaction pipeline calls another service over HTTPS, such as a [ph-eye](system_requirements.md) instance behind its own certificate.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `TLS_TRUST_ALL_ENABLED` | Whether outbound HTTPS from the redaction pipeline accepts any certificate from any host, skipping certificate and hostname verification. **Disabled by default.** Set to `true` only for a self-signed service on a private network; with it on those connections are interceptable by anything on the path. Philter logs a warning at startup while it is enabled. | `false` |

Prefer adding the service's issuing certificate to the JVM truststore over enabling this. Where the certificate is `ph-eye.crt`, that means importing it into a truststore and pointing the JVM at it:

```
keytool -importcert -alias ph-eye -file ph-eye.crt \
  -keystore /opt/philter/ssl/truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
```

```yaml
services:
  philter:
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Djavax.net.ssl.trustStore=/opt/philter/ssl/truststore.p12
        -Djavax.net.ssl.trustStorePassword=changeit
```

That keeps verification on and trusts exactly the one certificate you intend, rather than all of them.

## API Access

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_BOOTSTRAP_API_KEY` | Optional API key to seed at startup so automation and turnkey deployments have a credential without using the dashboard. Must be `sk_` followed by 32 alphanumeric characters (generate one however you provision secrets). When set, it is assigned to the `admin` user, but only if that user has no API keys at all (active or archived), so it is seeded once on a fresh install and never resurrected after you create or revoke a key of your own. It is created with every [scope](account/api_keys.md#scopes); narrow it in the dashboard or replace it with a key scoped to what your automation needs. Authentication stays enabled. While the bootstrap key is in use, the dashboard shows a warning on login and surfaces the key on the API Keys page. Rotate or revoke it in the dashboard when it is no longer needed. | (empty; UI key creation only) |
| `ADMIN_CROSS_USER_ACCESS_ENABLED` | Whether an administrator may view or act on **other** users' resources (their contexts, policies, custom lists, documents, and redaction ledger) via the API `owner` parameter and the admin "All …" dashboard tabs. **Disabled by default**, so an admin sees only their own data, like any user; set to `true` to opt in. Does not affect ordinary admin functions such as user management. | `false` |
| `LEDGER_DELETION_ENABLED` | Whether [redaction ledger](redaction/ledgers.md) entries may be deleted at all, through `DELETE /api/ledger` or the Redaction Ledgers dashboard. **Disabled by default**: when unset, no ledger evidence can be deleted through Philter and the dashboard controls are hidden. Deletion is additionally restricted to administrators, and [legal holds](redaction/legal_holds.md) still block it. Deleting another user's ledger requires `ADMIN_CROSS_USER_ACCESS_ENABLED` as well. | `false` |
| `PROVISIONING_API_ENABLED` | Whether the [provisioning endpoints](api_and_sdks/api/provisioning_api.md) (`POST /api/users` and `POST /api/users/{username}/api-keys`) exist, so automation can create a user and mint an API key for a user it is not signed in as. **Disabled by default**: when unset both endpoints answer `404 Not Found` and nothing reachable over the API or in the database can turn them on. Creating a credential in the dashboard forces the caller through the login and its MFA, which is why that stays the normal path. When enabled the endpoints still require an administrator and the `users:write` or `api-keys:write` scope, cannot create an administrator, cannot grant a key a scope the calling key does not hold, and audit every creation. | `false` |

## Auditing

Philter records security-relevant actions to an [audit log](auditing.md). Audit events fall into two
groups, and only one of them can be switched off.

**Security events are always recorded** and cannot be disabled: authentication failures, API key and
user account changes, policy changes, ledger access, export and deletion, admin cross-user actions,
legal holds, and signing key lifecycle. Disabling the audit trail is a compliance decision rather
than a tuning setting, so Philter does not offer it.

**Redaction-activity events are optional.** Two are recorded per redaction
(`document_redaction_initiated` and `document_redaction_completed`). They are the only audit events
on the redaction path and the only unbounded source of growth in the audit log, so a deployment
running Philter as a plain redaction engine can turn them off. Doing so does not reduce what a
security review can see; it removes the per-redaction volume.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `AUDIT_REDACTION_EVENTS_ENABLED` | Whether the two per-redaction audit events are recorded. Set to `false` for a lean, high-volume deployment. Security events are unaffected. | `true` |

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
curl -k -X DELETE -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger?older_than_days=90"
```

Ledger collections must have no TTL indexes. Startup rejects incompatible schemas; it does not drop indexes or migrate data.

## Asynchronous Documents and Webhooks

Records for asynchronous (PDF) redactions and outbound webhook deliveries are expired automatically by MongoDB TTL indexes.

Required TTL indexes are verified at startup. Conflicting options or unexpected expiry fields prevent startup. The unreleased 4.0 schema is created directly without migrations. Job retention uses `retention_at` after notification acknowledgement; webhook retention uses `completed_at` for either terminal outcome.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PENDING_DOCUMENTS_TTL_SECONDS` | How long to keep terminal asynchronous records after notification acknowledgement. | `604800` (7 days) |
| `WEBHOOK_DELIVERIES_TTL_SECONDS` | How long to keep delivered or failed webhook records after completion. | `2592000` (30 days) |
| `DOCUMENT_CLAIM_LEASE_SECONDS` | How long a worker owns an asynchronous document job before another worker may recover it. Must be positive; a non-positive value prevents startup. A worker that stops mid-redaction parks the job until this expires, so a deployment doing short redactions may want less than the default. A worker renews its lease every third of this value (at most every 60 seconds) while it is still working, so a long redaction is not interrupted by a short lease. | `600` (10 minutes) |
| `WEBHOOK_CLAIM_LEASE_SECONDS` | How long a worker owns a webhook attempt before another worker may recover it. Must be positive; allow enough time for the configured HTTP timeouts and processing overhead. Abandoned claims count toward the eight-attempt limit. | `300` (5 minutes) |
| `WEBHOOK_RESPONSE_TIMEOUT_SECONDS` | How long to wait for your endpoint to respond after the request is sent. A receiver that exceeds this is treated as a failed attempt and retried. Your endpoint should acknowledge quickly and do its work asynchronously. | `10` |
| `WEBHOOK_CONNECT_TIMEOUT_SECONDS` | How long to wait to establish the TCP connection (and TLS handshake) to your endpoint. | `5` |
| `WEBHOOK_POOL_TIMEOUT_SECONDS` | How long to wait for a free connection from the outbound connection pool. | `5` |

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
| `MAX_FILE_SIZE_BYTES` | Maximum request body size, in bytes, for the endpoints that accept a document to redact: `POST /api/filter` and `POST /api/explain`. | `10485760` (10 MB) |
| `MAX_FILE_SIZE_BYTES_OTHER` | Maximum request body size, in bytes, for every other `POST` and `PUT`. These carry configuration (policies, contexts, lists), not documents. The default accommodates the largest body these endpoints accept: a [redact lists](redaction/redact_lists.md) `POST` replaces both lists at once, so at the documented maximum of 1,000 terms of 100 characters per list it reaches roughly 203 KB. | `262144` (256 KB) |
| `PHEYE_ENDPOINT` | The endpoint of the ph-eye NER service used by policies that perform named-entity recognition. | (none) |

## Output Signing

Philter can sign `POST /api/filter` (text) and `POST /api/explain` responses with an ES256 JWT in the `X-Philter-Signature` response header. Signing is **disabled by default**; enable it in the dashboard **Admin** → **Admin Settings** page. See [Output Signing](output_signing.md) for full documentation.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_SIGNING_KEY_PATH` | Absolute path to a PKCS8 PEM private key file (`BEGIN PRIVATE KEY` format) to use instead of the auto-generated MongoDB key. When set, the file is read on startup; the MongoDB signing key is not used. The file must be accessible on every node. Leave unset to let Philter generate and manage the key automatically. | (none; auto-generate) |

## PII Drift Monitoring (Phield)

Philter can optionally publish per-redaction **PII type counts** to a [Phield](https://github.com/philterd/phield) drift monitor. Only counts and the source, organization, and context labels are sent; the redacted text and its replacements never leave Philter. This is configured in the dashboard **Admin** settings (enable, Phield URL, source id, organization, and the API key Phield requires when it is run with `PHIELD_API_KEY` set), not via environment variables. See [PII Drift Monitoring with Phield](phield.md).

### Bootstrap and in-memory capacity

| Setting | Behavior | Default |
|---|---|---|
| `PHILTER_BOOTSTRAP_ADMIN_PASSWORD` | Private first-login password; required when the `admin` account does not exist. Minimum 16 characters, maximum 72 UTF-8 bytes. `compose.sh` generates it in `.env`. | None |
| `IN_MEMORY_CACHE_MAX_ENTRIES` | Positive maximum across strings, hash containers, and hash fields. | `100000` |
| `IN_MEMORY_CACHE_MAX_BYTES` | Positive maximum accounted retained cache size, including conservative object overhead and UTF-16 string data. This is not a JVM heap limit. | `67108864` |

At capacity, ordinary cache insertions are skipped and unsuccessful refreshes discard stale values. Live login-failure counters are never evicted to admit new entries. Counter overflow blocks all dashboard logins for the failure window, including users whose counters could not be retained. Expiry and deletion release capacity. `LOGIN_MAX_ATTEMPTS` and `LOGIN_LOCKOUT_SECONDS` must be positive.

## Async queue admission

All limits must be positive and identical across instances. Limits count PENDING and PROCESSING jobs and their input bytes; terminal jobs release capacity.

| Variable | Default | Limit |
|----------|---------|-------|
| `ASYNC_QUEUE_MAX_JOBS` | `256` | Global active jobs |
| `ASYNC_QUEUE_MAX_USER_JOBS` | `32` | Active jobs per account |
| `ASYNC_QUEUE_MAX_BYTES` | `2147483648` | Global active input bytes |
| `ASYNC_QUEUE_MAX_USER_BYTES` | `268435456` | Active input bytes per account |

Owner capacity returns HTTP 429; global capacity and admission contention return HTTP 503. Both include `Retry-After: 5`. Shared MongoDB admission serialization prevents concurrent instances exceeding limits. Claims interleave account scheduling rounds, with submission time breaking ties. Micrometer exposes `philter.async.queue.jobs`, `philter.async.queue.bytes`, and `philter.async.queue.oldest.seconds`. Retained results and execution snapshots are separate from active queue capacity.

An interrupted or ambiguous admission retains its guard and blocks further submissions with 503. It does not expire automatically. For recovery, quiesce **all admission writers and their outstanding database operations**, inspect the job insertion outcome, then conditionally unset `token` and `started_at` in `queue_admission` for `_id: "queue"`, matching the observed token. Never clear a guard while its writer may still insert. Resume writers only after resolving that operation; capacity is recomputed from stored active jobs.
