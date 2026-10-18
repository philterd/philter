# Settings

Philter has settings to control how it operates. The settings and how to configure each are described below.

> The configuration for the types of sensitive information that Philter identifies are defined
> in [filter policies](policies/filter_policies.md) outside of Philter's configuration properties described on this page.

## Configuring Philter

Philter reads its settings from environment variables. There is no settings file.

## Database Settings

Philter stores policies and other data in a SQLite file by default, which suits a single Philter instance, or in MongoDB, which several instances can share. See [Database](database.md) for what is stored, for [SQLite](database.md#sqlite), and for MongoDB connection-string examples (authentication, replica sets, and Atlas).

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `STORAGE_TYPE` | Where Philter stores its data: `sqlite`, a file for a [single Philter instance](database.md#sqlite) with no database server, or `mongodb`, which several instances can share. Philter does not start with any other value, or when `MONGODB_CONNECTION_STRING` is set and `STORAGE_TYPE` is not, so a deployment that used MongoDB does not start on a new, empty SQLite database. | `sqlite` |
| `SQLITE_PATH` | With SQLite storage, the database file. Its directory must exist and be writable. A relative path, such as the default, is relative to the directory Philter is started in, so outside Docker set an absolute path; Philter logs where the database is at startup, with a warning when the path is relative. The Docker image sets it to `/opt/philter/data/philter.db`; mount a volume at `/opt/philter/data` to keep the data. | `philter.db` (`/opt/philter/data/philter.db` in the Docker image) |
| `MONGODB_CONNECTION_STRING` | With `STORAGE_TYPE=mongodb`, the MongoDB connection string. | `mongodb://localhost:27017` |
| `SCHEMA_MIGRATION_WAIT_SECONDS` | How long an instance waits at startup for another instance that is [migrating the data](database.md#schema-version-and-migrations) before it stops with an error. | `600` |

## Encryption

Philter encrypts sensitive data at rest and requires an encryption key. Encryption is applied per collection; see [what is encrypted](database.md#what-is-encrypted-at-rest). Philter will not start if the key is missing or invalid. The `compose.sh` script in the repository generates one into a `.env` file on first run and reuses it after that, which is the simplest way to keep the key stable across restarts. It generates the bootstrap API key into the same file.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_ENCRYPTION_KEY` | A base64-encoded 32-byte (AES-256) key. Generate one with `openssl rand -base64 32`. Each record is encrypted with its own random data key, stored wrapped under this key, so this value is required to read any encrypted data. Use the same value across restarts and instances, and back it up: if it is lost or changed, existing encrypted data cannot be recovered and Philter will not start. | (none; required) |

## Cache Settings

The cache is used for API key and context caching. What Philter stores in Valkey is encrypted under `PHILTER_ENCRYPTION_KEY`; see [Protecting the cache](caching.md#protecting-the-cache). Philter supports Valkey/Redis as the backend cache. The cache is **optional for a single instance** and **required when more than one instance shares a database**, with every instance using the same one. When `CACHE_HOSTNAME` is unset or blank, Philter uses an in-memory cache instead. The in-memory cache is ephemeral (it is not shared across instances and is lost on restart) and a warning is printed at startup. Configure Valkey for a durable, shared cache.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `CACHE_HOSTNAME` | The hostname or IP address of the Valkey cache. Leave unset to use an in-memory cache. | (empty; in-memory) |
| `CACHE_PORT` | The Valkey port. | `6379` |
| `CACHE_PASSWORD` | The Valkey password. Philter warns at startup when a cache on another host has none. See [Protecting the cache](caching.md#protecting-the-cache). | (empty) |
| `CACHE_SSL` | Whether to use TLS for communication with the Valkey cache. Philter warns at startup when a cache on another host is used without it. | `false` |
| `SCHEDULER_POOL_SIZE` | Threads shared by Philter's four scheduled background tasks: claiming and redacting asynchronous documents, reconciling their completion notifications, delivering webhooks, and expiring session keys. Tasks wait for a free thread, so with a single thread a slow document delays webhook delivery. | `2` |
| `API_KEY_CACHE_TTL_SECONDS` | How long a resolved API key is cached. Revoking a key through the API evicts it immediately; this bounds how long a key revoked out-of-band keeps working. See [Caching](caching.md). | `60` |
| `REDACTION_CACHE_TTL_SECONDS` | How long the filtering endpoints keep a user's policy and always/never redact lists in-process. An edited or deleted policy or list applies to the next request on every instance regardless: a cached copy is used only while its revisions match the stored ones. Never written to Valkey/Redis. See [Caching](caching.md). | `60` |
| `ADMIN_SETTINGS_CACHE_TTL_SECONDS` | How long an instance caches the admin settings (output signing, Phield, Diffuse, the webhook allowlist, and MFA availability and requirement) before re-reading them. They are read on every redaction, so caching keeps that off the database. A change made through this instance's [Settings API](api_and_sdks/api/settings_api.md) applies immediately; one made on another instance is picked up within this window. | `60` |

## Metrics

Philter exposes metrics in Prometheus format at `/actuator/prometheus`. See [Monitoring and Logging](monitoring_and_logging.md). There are no metrics-storage settings to configure.

## TLS

Philter serves HTTPS on port 8080. The Docker image generates a self-signed certificate the first time it starts and writes it to `/opt/philter/ssl/philter.p12`. That certificate is not signed by a certificate authority, so clients must skip verification (`curl -k`). It encrypts the connection but does not prove Philter's identity, which is why every example in this documentation passes `-k`.

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
| `SSL_KEYSTORE_PASSWORD` | The keystore password. The default is public, so it does not protect the private key: the file's permissions do. A keystore the container generates is readable only by Philter's user; give one you supply the same permissions. | `philter` |
| `SSL_KEYSTORE_TYPE` | The keystore format, `PKCS12` or `JKS`. | `PKCS12` |
| `SSL_KEY_ALIAS` | The alias of the key to use within the keystore. | `philter` |
| `SSL_CERTIFICATE_HOSTNAME` | The hostname recorded in the generated certificate's subject and subject alternative name. Used only when generating one. | `localhost` |

### Client certificates (mutual TLS)

Philter can require each client to present a certificate, in addition to its API key, when it serves HTTPS itself. A client whose certificate was not issued by a certificate in Philter's trust store is refused during the TLS handshake, before any request reaches the API. This applies to every path on the port, including `/api/health` and `/actuator/prometheus`, so health checks and metrics scrapers need a client certificate too.

A client certificate does not identify a Philter user and does not replace an API key: a client still needs a valid API key for every endpoint that requires one. The [Java SDK](api_and_sdks/sdks.md) presents a client certificate with `withClientCertificate`.

Put the certificates that issue your client certificates, usually your own certificate authority's, in a PKCS12 trust store:

```
keytool -importcert -alias clients-ca -file clients-ca.crt \
  -keystore /opt/philter/ssl/clients.p12 -storetype PKCS12 -storepass changeit -noprompt
```

Then mount it and turn client authentication on:

```yaml
services:
  philter:
    environment:
      SSL_CLIENT_AUTH: need
      SSL_TRUSTSTORE: /opt/philter/ssl/clients.p12
      SSL_TRUSTSTORE_PASSWORD: changeit
    volumes:
      - ./clients.p12:/opt/philter/ssl/clients.p12:ro
```

Check it with a client certificate and its key:

```
curl --cacert philter.crt --cert client.crt --key client.key https://localhost:8080/api/health
```

Trust only the certificate authorities you use for Philter's clients. Every certificate one of them issues is accepted.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `SSL_CLIENT_AUTH` | `need` requires a client certificate issued by a certificate in the trust store. `want` asks for one but serves clients without one, or with one the trust store does not vouch for, so it does not restrict access. `none` asks for none. With `need` or `want`, Philter refuses to start unless `SSL_ENABLED` is `true` and `SSL_TRUSTSTORE` is set. | `none` |
| `SSL_TRUSTSTORE` | Path to the trust store holding the certificates that issue client certificates. Used only when `SSL_CLIENT_AUTH` is `need` or `want`. | None |
| `SSL_TRUSTSTORE_PASSWORD` | The trust store password. | None |
| `SSL_TRUSTSTORE_TYPE` | The trust store format, `PKCS12` or `JKS`. | `PKCS12` |

When TLS is terminated in front of Philter, require client certificates at the load balancer, ingress, or service mesh instead: Philter only sees connections from the proxy.

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
| `PHILTER_BOOTSTRAP_API_KEY` | The `admin` user's first API key, and the only way to get a first credential. Must be `sk_` followed by 32 letters and digits (generate one however you provision secrets). **Required while the `admin` user has never had an API key**: Philter does not start if it is unset or malformed. Once the `admin` user has had any key, active or revoked, it is ignored, so it is seeded once on a fresh install and never re-created after you revoke it. It is created with every [scope](account/api_keys.md#scopes); use it to create narrower keys with the [API Keys API](api_and_sdks/api/api_keys_api.md). To retire it, create a replacement administrator key holding every scope, then revoke the bootstrap key with the replacement: a key cannot revoke itself or a key holding scopes it lacks. | (none) |
| `PASSWORD_SIGN_IN_ENABLED` | Whether people may [sign in](api_and_sdks/api/sign_in_api.md) with a username and password to get a session key. **Disabled by default**: while it is off, `POST /api/sign-in` returns `404 Not Found`, so a deployment without a user interface exposes no login endpoint. Set it to `true` when running a user interface such as Philter UI. | `false` |
| `SIGN_IN_MAX_FAILURES` | How many failed [sign-ins](api_and_sdks/api/sign_in_api.md#lockout-and-rate-limiting) for a username, within `SIGN_IN_LOCKOUT_MINUTES`, lock it. Philter does not start if it is zero or negative; a value that is not a whole number is ignored with a warning, and the default is used. | `5` |
| `SIGN_IN_LOCKOUT_MINUTES` | How long a locked username is refused, and the window failures are counted in. Philter does not start if it is zero or negative; a value that is not a whole number is ignored with a warning, and the default is used. | `15` |
| `SIGN_IN_RATE_LIMIT_PER_MINUTE` | How many sign-in requests each client address may make a minute, across both sign-in steps. Philter does not start if it is zero or negative; a value that is not a whole number is ignored with a warning, and the default is used. IPv6 clients are counted by /64. Account lockout is stored with the user. | `20` |
| `SESSION_KEY_IDLE_TIMEOUT_MINUTES` | How long a [session key](account/api_keys.md#session-keys), the key issued when a person signs in, stays valid without a request. Each request starts it again. Philter does not start if it is zero or negative; a value that is not a whole number is ignored with a warning, and the default is used. Applies to keys issued after a change. | `30` |
| `SESSION_KEY_MAX_LIFETIME_MINUTES` | How long a session key stays valid after it is issued, however active it is. Philter does not start if it is zero or negative; a value that is not a whole number is ignored with a warning, and the default is used. Applies to keys issued after a change. | `720` (12 hours) |
| `ADMIN_CROSS_USER_ACCESS_ENABLED` | Whether an administrator may view or act on **other** users' resources (their contexts, policies, custom lists, documents, and redaction ledger) via the API `owner` parameter and the `all_users` parameter on the policy, context, custom list, ledger, and legal hold listings. **Disabled by default**, so an admin sees only their own data, like any user; set to `true` to opt in. While it is off, an administrator can create an API key for another user only if that user has never had one (counting revoked and session keys), which provisions a new user; a key for a user who may already hold data would let the administrator act as them. Each request that reaches another user's resources this way, and each such key, is recorded as an [`admin_cross_user_access`](auditing.md) audit event. Does not affect other admin functions such as user management, so it is not a complete boundary: an administrator who sets or resets a user's password, and removes their MFA, can sign in as that user when `PASSWORD_SIGN_IN_ENABLED` is on. That is recorded as `user_password_set` or `user_password_reset` and `user_mfa_removed`, not `admin_cross_user_access`, and a reset stops the user's own password and sessions working. See [Sign-in Security](sign_in_security.md#first-sign-in-and-resets). | `false` |
| `LEDGER_DELETION_ENABLED` | Whether [redaction ledger](redaction/ledgers.md) entries may be deleted at all, through `DELETE /api/ledger`. **Disabled by default**: when unset, no ledger evidence can be deleted through Philter. Deletion is additionally restricted to administrators, and [legal holds](redaction/legal_holds.md) still block it. Deleting another user's ledger requires `ADMIN_CROSS_USER_ACCESS_ENABLED` as well. | `false` |
| `TRUSTED_PROXIES` | The load balancers and proxies whose `X-Forwarded-For` header Philter believes when recording the client IP address of an API request in the [audit log](auditing.md): a comma-separated list of IP addresses and CIDR ranges. A request from one of them is attributed to the client address the header names, reading the header from the right and skipping trusted proxies, since each proxy appends the address it received from and a client can write the leftmost entries itself. A request from any other address is attributed to the connection's address, and the header is ignored. Only IP addresses are recorded; a hostname or other text in the header is ignored, and nothing is looked up in DNS. The default trusts only loopback, so a proxy on the same host works without configuration and no other client, including one on your internal network, can choose its recorded address. **Behind a load balancer or ingress on another host, or with an application on another host that passes its users' addresses, such as [Philter UI](https://github.com/philterd/philter-ui), set this to their addresses**: until then, every request is recorded as coming from them, and all clients share their [sign-in rate limit](sign_in_security.md#lockout-and-rate-limiting). Only this setting decides: Philter does not let its embedded web server act on `X-Forwarded-For`, including on Kubernetes and other cloud platforms where Spring Boot would otherwise trust it from any private address. Philter logs a warning, once, the first time it ignores the header, naming the address it came from. Entries that are not an address or range are logged and ignored. | `127.0.0.0/8, ::1` |

## Auditing

Philter records security-relevant actions to an [audit log](auditing.md). Audit events fall into two
groups, and only one of them can be switched off.

**Security events are always recorded** and cannot be disabled: authentication failures, API key and
user account changes, policy changes, ledger access, export and deletion, admin cross-user actions,
legal holds, and signing key lifecycle. Disabling the audit trail is a compliance decision rather
than a tuning setting, so Philter does not offer it.

**Redaction-activity events are optional.** A redaction records `document_redaction_completed`; an
asynchronous document submission also records `document_redaction_initiated` when it is queued. They are the only audit events
on the redaction path and the only unbounded source of growth in the audit log, so a deployment
running Philter as a plain redaction engine can turn them off. Doing so does not reduce what a
security review can see; it removes the per-redaction volume.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `AUDIT_REDACTION_EVENTS_ENABLED` | Whether the redaction-activity audit events are recorded. Set to `false` for a lean, high-volume deployment. Security events are unaffected. | `true` |

## Redaction Ledger

Whether a redaction is recorded in the [redaction ledger](redaction/ledgers.md) is controlled per context by the `ledger` option set when [creating or updating a context](api_and_sdks/api/contexts_api.md). It is disabled by default, so redactions made in a context are not written to the ledger unless the context has it enabled.

**Ledger entries never expire on their own.** They are governance evidence, so they are removed only by a deliberate deletion: an administrator calling `DELETE /api/ledger` or `DELETE /api/ledger/{documentId}`. Both require `LEDGER_DELETION_ENABLED=true` (see [API Access](#api-access) above), are refused while a [legal hold](redaction/legal_holds.md) covers the evidence, and are recorded in the [audit log](auditing.md).

To enforce a retention period, schedule the purge endpoint. This gives you time-based retention that is still admin-only, hold-aware, and audited:

```bash
# Daily: delete this account's ledger chains older than 90 days.
curl -k -X DELETE -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger?older_than_days=90"
```

Ledger collections must have no TTL indexes. Startup rejects incompatible schemas; it does not drop indexes or migrate data.

## Asynchronous Documents and Webhooks

Records for asynchronous (PDF) redactions and outbound webhook deliveries are removed automatically once they are past these retention periods: by TTL indexes with MongoDB, and by a check that runs every minute with [SQLite](database.md#sqlite).

Required TTL indexes are verified at startup. Conflicting options or unexpected expiry fields prevent startup. The unreleased 4.0 schema is created directly without migrations. Job retention uses `retention_at` after notification acknowledgement; webhook retention uses `completed_at` for either terminal outcome.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PENDING_DOCUMENTS_TTL_SECONDS` | How long to keep a finished asynchronous redaction's record, including its encrypted redacted document, after the job completed or failed and its notification was queued. Deleting the document removes it sooner. See [what Philter keeps](api_and_sdks/api/documents_api.md#what-philter-keeps-and-for-how-long). | `604800` (7 days) |
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
| `MAX_FILE_SIZE_BYTES_OTHER` | Maximum request body size, in bytes, for every other request, whatever its method. These carry configuration (policies, contexts, lists), not documents. The default accommodates the largest body these endpoints accept: a [redact lists](redaction/redact_lists.md) `POST` replaces both lists at once, so at the documented maximum of 1,000 terms of 100 characters per list it reaches roughly 203 KB. | `262144` (256 KB) |
| `PDF_MAX_PAGES` | The most pages a PDF may have to be redacted. A larger PDF is refused with `413` and the reason `payload_too_large`, or, when queued, fails with that message. `0` or less sets no limit. Philter does not start if it is not a whole number. | `2000` |
| `PDF_MAX_PIXELS_PER_PAGE` | The most pixels one PDF page may render to at the policy's `dpi`. Each redacted page is rendered to an image in memory, so without this a small PDF with a very large page size, or a policy with a high `dpi`, could exhaust Philter's memory. A PDF with a larger page, or a page that uses an image larger than this by the size the image declares, is refused like one with too many pages. A Letter page at 600 DPI is about 34 million pixels. `0` or less sets no limit. Philter does not start if it is not a whole number. | `50000000` |
| `PDF_MAX_STREAM_BYTES` | The most bytes one compressed stream in a PDF page may decompress to. A small PDF can hold a stream that decompresses to far more than its size, which would exhaust memory as it is read. Streams compressed with Flate alone, the most common kind, are checked before the PDF is read, and a PDF with a larger one is refused like one with too many pages. Streams compressed another way, inline images, a JPEG larger than it declares, and compressed object streams are not checked; see [Memory](system_requirements.md#memory) for the backstop. `0` or less sets no limit. Philter does not start if it is not a whole number. | `268435456` (256 MB) |
| `PDF_MAX_CONCURRENT` | How many PDFs one Philter instance redacts at once, counting synchronous requests and queued documents together. Each page being redacted is rendered to an image in memory, so allow about 500 MB of heap for each at the default limits. A synchronous PDF (`async=false`) that arrives while all are in use is refused with `503 Service Unavailable` and `Retry-After: 5`; a queued document waits its turn. `0` or less sets no limit. Philter does not start if it is not a whole number. | `2` |
| `PHEYE_ENDPOINT` | The endpoint of the ph-eye NER service, used by a policy's ph-eye filter when the policy sets no endpoint. | `http://philter-ph-eye-1:5000/` |
| `PHEYE_BEARER_TOKEN` | Bearer token sent to ph-eye. It is sent only to `PHEYE_ENDPOINT`, never to another endpoint a policy names, and a policy cannot set a token of its own. | (none) |
| `PHEYE_ALLOWED_ENDPOINTS` | Other ph-eye endpoints a policy may name, separated by commas. A trailing slash is ignored. A policy naming any endpoint other than these and `PHEYE_ENDPOINT` is refused. See [Redaction services](#redaction-services). | (none) |
| `PHEYE_MODELS_DIRECTORY` | The directory of local ph-eye models. Each subdirectory holding a complete model is a model a policy can name in its ph-eye `modelPath`, listed by [`GET /api/models`](api_and_sdks/api/models_api.md). The Docker image includes `ph-eye-pii-en-small` there, which the default policy names. That model is licensed CC-BY-4.0 and was trained on NVIDIA's [Nemotron-PII](https://huggingface.co/datasets/nvidia/Nemotron-PII) dataset; its model card, with the attribution the license requires, is `README.md` in its folder and at [philterd/ph-eye-pii-en-small](https://huggingface.co/philterd/ph-eye-pii-en-small). Philter does not start if it is set and is not a directory. | `/opt/philter/models` |
| `PHEYE_ALLOWED_MODEL_PATHS` | Other local GLiNER model directories a policy's ph-eye `modelPath` may give as a path, separated by commas. A policy giving any other path, outside `PHEYE_MODELS_DIRECTORY`, is refused. | (none) |
| `PHEYE_ONNX_DEVICE` | Where local ph-eye models run: `cpu`, `cuda` (GPU 0), or `cuda:<n>`. The Philter image includes ONNX Runtime's CPU package, so `cuda` there logs a warning and runs on the CPU; a GPU needs a build with `onnxruntime_gpu` and CUDA. See [phileas-pheye-onnx's configuration](https://philterd.github.io/phileas-pheye-onnx/latest/configuration/#gpu). Philter does not start if it is any other value. | `cpu` |
| `PHEYE_ONNX_THREADS` | Threads each local ph-eye model uses for one detection. More lower one request's latency; fewer give more throughput when many requests run at once, especially under a CPU quota such as a Kubernetes CPU limit. Philter does not start if it is not a positive whole number. | The CPUs available to Philter |
| `GENERATOR_ALLOWED_ENDPOINTS` | Endpoints a policy's [replacement generators](policies/filter_strategies.md#the-map_replace-filter-strategy) may name, separated by commas. A trailing slash is ignored. A policy naming any other endpoint is refused. | (none) |
| `POLICY_FILES_DIRECTORIES` | Directories, separated by commas, that the files a policy names must be inside. Unset, a policy may name no file. Each must exist and be a directory, and cannot be the filesystem root, or Philter refuses to start. See [Policy files](#policy-files). | (none) |
| `POLICY_FILES_MAX_BYTES` | The largest file, in bytes, a policy may name. Must be a positive number, or Philter refuses to start. Set these two rather than Phileas's own `policy.files.*` properties: Philter refuses to start if one of those is set as an environment variable or system property, since it would override them. | `10485760` (10 MB) |
| `POLICY_ALLOWED_ENV_KEYS` | Environment variables, separated by commas, that a policy may name as `env:NAME` for a `crypto` or `fpe` key or tweak. Unset, a policy may name no variable. Philter refuses to start if a name is not a valid variable name or is one of Philter's own secrets. Set this rather than Phileas's own `policy.env.allowed` property: Philter refuses to start if that is set as an environment variable or system property. See [Environment variables in a policy](#environment-variables-in-a-policy). | (none) |
| `PHEYE_TIMEOUT` | Connection-request and response timeout, in seconds, for calls to ph-eye, used when the policy sets none. | `600` |

### Redaction services

Any user can write a policy, so the services and files a policy reaches are the operator's decision, not the policy author's. A policy is refused, when it is saved and before a redaction or asynchronous submission uses it, if it names a ph-eye `endpoint` other than `PHEYE_ENDPOINT` or one in `PHEYE_ALLOWED_ENDPOINTS`, sets a ph-eye `bearerToken`, names a `modelPath` that is neither a model in `PHEYE_MODELS_DIRECTORY` nor in `PHEYE_ALLOWED_MODEL_PATHS`, or names a generator `endpoint` not in `GENERATOR_ALLOWED_ENDPOINTS`. Without this, a policy could send the text being redacted, or the deployment's ph-eye token, to a server of its author's choosing. Philter does not follow redirects from these services, so an allowed endpoint cannot redirect a request elsewhere.

### Policy files

A policy can name files of terms and replacements: a custom dictionary's `files`, a filter's `ignoredFiles`, the top-level `ignored` `files`, and a `MAP_REPLACE` strategy's `mappingFiles`. A file must be a regular file inside one of the directories in `POLICY_FILES_DIRECTORIES`, after symbolic links and `..` are resolved, and no larger than `POLICY_FILES_MAX_BYTES`. With `POLICY_FILES_DIRECTORIES` unset, the default, a policy may name no file. A policy naming any other file is refused with `400 Bad Request` when it is saved, and before a redaction or asynchronous submission uses it. The message is the same whether the file is missing, outside these directories, or not a regular file.

**Put only shared reference data in these directories.** The restriction is per deployment, not per user: anyone who can write a policy can use any file in them. A dictionary turns a file's lines into terms whose presence a redaction reveals, and `MAP_REPLACE` returns a mapping file's values outright. Never put secrets or one user's private data there. For terms that belong to one user, use [custom lists](redaction/custom_lists.md), which are stored per user and encrypted.

In Docker, mount a directory, read-only, and name it:

```yaml
services:
  philter:
    environment:
      POLICY_FILES_DIRECTORIES: /opt/philter/policy-files
    volumes:
      - ./policy-files:/opt/philter/policy-files:ro
```

### Environment variables in a policy

A policy's `crypto` and `fpe` keys can name an environment variable as `env:NAME` instead of holding the key itself, so the key is not stored in the policy. Philter reads that variable, so the variables a policy may name are the operator's decision: a policy may name only a variable in `POLICY_ALLOWED_ENV_KEYS`, and with it unset, the default, no variable. A policy naming any other variable is refused with `400 Bad Request` when it is saved, before a redaction or asynchronous submission uses it, and when [re-identification](redaction/re-identification.md) uses it. The variable is not read, so the message is the same whether or not it is set. Philter's own secrets, such as `PHILTER_ENCRYPTION_KEY`, `PHILTER_BOOTSTRAP_API_KEY`, `CACHE_PASSWORD`, `MONGODB_CONNECTION_STRING`, `PHEYE_BEARER_TOKEN`, and the TLS store passwords, can never be listed.

**Every policy author can use every listed variable.** The list is per deployment, not per user: anyone who can write a policy can name a listed key and use it to encrypt, or [re-identify](redaction/re-identification.md) values encrypted with it. List only keys meant to be shared by everyone who writes policies. A user who needs a key of their own can leave `fpe` out to use the per-user FPE key Philter manages. `crypto` has no per-user key: a `CRYPTO_REPLACE` key not shared through this list must be written into the policy, where it is stored in the clear (see [what is encrypted at rest](database.md#what-is-encrypted-at-rest)).

```yaml
services:
  philter:
    environment:
      POLICY_ALLOWED_ENV_KEYS: PHILTER_CRYPTO_KEY,PHILTER_FPE_KEY,PHILTER_FPE_TWEAK
      PHILTER_CRYPTO_KEY: <64 hex characters>
```

## Output Signing

Philter can sign `POST /api/filter` (text) and `POST /api/explain` responses with an ES256 JWT in the `X-Philter-Signature` response header. Signing is **disabled by default**; enable it with the [Settings API](api_and_sdks/api/settings_api.md). See [Output Signing](output_signing.md) for full documentation.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_SIGNING_KEY_PATH` | Absolute path to a PKCS8 PEM private key file (`BEGIN PRIVATE KEY` format) to use instead of the auto-generated key stored in the database. When set, the file is read on startup; the stored signing key is not used. The file must be accessible on every node. Leave unset to let Philter generate and manage the key automatically. | (none; auto-generate) |

## PII Drift Monitoring (Phield)

Philter can optionally publish per-redaction **PII type counts** to a [Phield](https://github.com/philterd/phield) drift monitor. Only counts and the source, organization, and context labels are sent; the redacted text and its replacements never leave Philter. This is configured with the [Settings API](api_and_sdks/api/settings_api.md) (enable, Phield URL, source id, organization, and the API key Phield requires when it is run with `PHIELD_API_KEY` set), not via environment variables. See [PII Drift Monitoring with Phield](phield.md).

### Bootstrap and in-memory capacity

| Setting | Behavior | Default |
|---|---|---|
| `PHILTER_BOOTSTRAP_API_KEY` | Required on first start; see [API Access](#api-access). `compose.sh` generates it in `.env`. | None |
| `IN_MEMORY_CACHE_MAX_ENTRIES` | Positive maximum across strings, hash containers, and hash fields. | `100000` |
| `IN_MEMORY_CACHE_MAX_BYTES` | Positive maximum accounted retained cache size, including conservative object overhead and UTF-16 string data. This is not a JVM heap limit. | `67108864` |

At capacity, ordinary cache insertions are skipped and unsuccessful refreshes discard stale values. Expiry and deletion release capacity.

## Async queue admission

All limits must be positive and identical across instances. Limits count PENDING and PROCESSING jobs and their input bytes; terminal jobs release capacity.

| Variable | Default | Limit |
|----------|---------|-------|
| `ASYNC_QUEUE_MAX_JOBS` | `256` | Global active jobs |
| `ASYNC_QUEUE_MAX_USER_JOBS` | `32` | Active jobs per account |
| `ASYNC_QUEUE_MAX_BYTES` | `2147483648` | Global active input bytes |
| `ASYNC_QUEUE_MAX_USER_BYTES` | `268435456` | Active input bytes per account |

Owner capacity returns HTTP 429; global capacity and admission contention return HTTP 503. Both include `Retry-After: 5`. Admission is serialized through the database, so instances sharing MongoDB cannot together exceed the limits. Claims interleave account scheduling rounds, with submission time breaking ties. Within an account, documents submitted with different [fairness keys](api_and_sdks/api/filtering_api.md#fairness-keys) take turns. Micrometer exposes `philter.async.queue.jobs`, `philter.async.queue.bytes`, and `philter.async.queue.oldest.seconds`. Retained results are separate from active queue capacity.

An interrupted or ambiguous admission retains its guard and blocks further submissions with 503. It does not expire automatically. With SQLite storage, Philter clears it when it next starts, since only a Philter that has stopped can have left it held, and records `storage_guard_recovered` in the audit log. With MongoDB, recover it by hand: quiesce **all admission writers and their outstanding database operations**, inspect the job insertion outcome, then conditionally unset `token` and `started_at` in `queue_admission` for `_id: "queue"`, matching the observed token. Never clear a guard while its writer may still insert. Resume writers only after resolving that operation; capacity is recomputed from stored active jobs.
