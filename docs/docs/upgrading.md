# Upgrading Philter

We recommend reviewing the [Philter Release Notes](https://www.philterd.ai/philter-release-notes/) prior to upgrading.

## Upgrading within 4.x

From 4.0 on, Philter records the version of its data layout and migrates the data itself when a new release needs it. There is nothing to run by hand.

1. **Back up the database** before upgrading. Migrations change data in place, and a database that a newer release has migrated cannot be used by an older one.
2. **Stop the instances running the old release**, then start the new one. Instances running the old release keep running against the migrated data, so replace them together rather than one at a time when the new release migrates.
3. **Check the log** of the first instance to start. It records the schema version it found and each migration it ran. Other instances starting at the same time wait for it.

To move a 4.x deployment from SQLite to MongoDB, for example to run several instances, use the `migrate-storage` command with Philter stopped; see [Moving from SQLite to MongoDB](database.md#moving-from-sqlite-to-mongodb). Upgrade first, so the SQLite database is at the schema version the release running the command expects.

If a migration fails, Philter does not start, and the log says which migration failed and why. The data stays at the last migration that completed; fix the cause, or run a release with a fix, and start again. A database written by a newer release stops an older release from starting, naming both versions, so roll back by restoring the backup rather than by starting the older release. See [Schema version and migrations](database.md#schema-version-and-migrations).

## Upgrading to 4.x

Philter 4 changes how Philter is configured, secured, and stores its data. Philter 3.x read `philter.properties`, loaded policies from a directory (or, in some releases, from S3 or OpenSearch), kept no database, and accepted requests without credentials unless client certificates were required. Philter 4 is configured with environment variables, keeps policies and everything else in a database, and requires an API key on every request.

Treat the upgrade as a new deployment: run Philter 4 alongside 3.x, move the policies and clients over, and retire 3.x once the clients work against Philter 4.

### Steps

1. **Deploy Philter 4.** Install Java 25, or use the `philterd/philter` image. Set `PHILTER_ENCRYPTION_KEY` and `PHILTER_BOOTSTRAP_API_KEY`, and choose storage: SQLite (the default) for a single instance, or MongoDB for several. See [Platform and configuration](#platform-and-configuration).
2. **Move the settings from `philter.properties` to environment variables**, using the [table below](#settings-from-philterproperties).
3. **Upload your policies** with the Policies API; see [Policies](#policies).
4. **Create the contexts your clients use.** A context named in a request must exist; see [Contexts](#contexts).
5. **Update your clients**: an API key on every request, trust for Philter 4's certificate, and the API changes listed under [Changes for clients](#changes-for-clients).
6. **Test your redaction workflows** against your own data, then retire the 3.x deployment.

### Platform and configuration

* **Java 25 is now required.** Philter 3.x ran on Java 17. See [System Requirements](system_requirements.md).
* **Settings are environment variables.** Philter 4 does not read `philter.properties`. See [Settings](settings.md).
* **Philter keeps its data in a database.** Philter 3.x had none. Philter 4 stores policies, contexts, users, API keys, ledgers, and the audit log in SQLite by default (`SQLITE_PATH`, one instance), or in MongoDB with `STORAGE_TYPE=mongodb` and `MONGODB_CONNECTION_STRING`, which several instances can share. Keep the SQLite file on a persistent volume and back it up. See [Database](database.md).
* **An encryption key is now required.** Set `PHILTER_ENCRYPTION_KEY` to a base64-encoded 32-byte (AES-256) key (generate one with `openssl rand -base64 32`). Philter will not start without a valid key. Each record is encrypted with its own random data key, stored wrapped under `PHILTER_ENCRYPTION_KEY`. Keep this value safe and use it consistently across restarts and instances: if it is lost or changed, existing encrypted data cannot be recovered and Philter will not start. See [Settings](settings.md#encryption).
* **The first credential is a bootstrap API key.** Set `PHILTER_BOOTSTRAP_API_KEY` (`sk_` followed by 32 letters and digits) for a new deployment: Philter does not start without it until the `admin` user has had an API key. See [Bootstrapping an API key](account/api_keys.md#bootstrapping-an-api-key-for-automation).
* **The Docker image generates its own certificate.** The published 3.x image served HTTPS on port 8080 with a self-signed certificate built into the image, so every container from it shared one key. The 4.x image also serves HTTPS on port 8080, but generates a self-signed certificate on its first start, so clients that trusted the 3.x certificate must trust the new one. For anything beyond evaluation, supply your own certificate with `SSL_KEYSTORE`, or terminate TLS in front of Philter and set `SSL_ENABLED=false`. An image built from the 3.x repository's own Dockerfile served plain HTTP; change those clients' URLs to `https://`. Running the JAR directly serves HTTP unless `SSL_ENABLED=true`. See [TLS](settings.md#tls).
* **There is no built-in user interface.** The 3.x `philter-ui` application and its image are not part of Philter 4. Philter 4 is administered through its API: users with the [Users API](api_and_sdks/api/users_api.md), keys with the [API Keys API](api_and_sdks/api/api_keys_api.md), deployment settings with the [Settings API](api_and_sdks/api/settings_api.md), and policies, contexts, ledgers, legal holds, and the audit log with their own APIs. [Philter UI](https://github.com/philterd/philter-ui), a separate browser interface in development, is being built to sign people in through Philter with their own username and password; see [Sign-in Security](sign_in_security.md).
* **Password sign-in, session keys, MFA, and lockout are new, and off unless configured.** Users can have passwords, a user interface can exchange them for expiring session keys, and TOTP multi-factor authentication and sign-in lockout protect them. New environment variables: `PASSWORD_SIGN_IN_ENABLED` (default `false`), `SESSION_KEY_IDLE_TIMEOUT_MINUTES`, `SESSION_KEY_MAX_LIFETIME_MINUTES`, `SIGN_IN_MAX_FAILURES`, `SIGN_IN_LOCKOUT_MINUTES`, and `SIGN_IN_RATE_LIMIT_PER_MINUTE`; see [Settings](settings.md#api-access). New admin settings: `mfaAvailable` and `mfaRequired`; see the [Settings API](api_and_sdks/api/settings_api.md). See [Sign-in Security](sign_in_security.md).

### Settings from philter.properties

Philter 3.x read these from `philter.properties`, or from an environment variable or system property of the same name. Their names changed between 3.x releases; both forms are listed.

| 3.x setting | Philter 4 |
|-------------|-----------|
| `server.port` | Philter 4 serves on port `8080`. Map another port in Docker or at your load balancer. |
| `server.ssl.*` | `SSL_ENABLED`, `SSL_KEYSTORE`, `SSL_KEYSTORE_PASSWORD`, `SSL_KEYSTORE_TYPE`, and `SSL_KEY_ALIAS`. See [TLS](settings.md#tls). |
| `server.ssl.client-auth` | `SSL_CLIENT_AUTH`, with `SSL_TRUSTSTORE` and `SSL_TRUSTSTORE_PASSWORD`. A client certificate no longer stands in for credentials: a client still needs an API key. See [Client certificates](settings.md#client-certificates-mutual-tls). |
| `policies.directory`, `filter.policies.directory`, `policies.s3.*`, `filter.policies.service`, `filter.policies.service.opensearch.*` | None. Policies are stored in Philter's database and managed with the [Policies API](api_and_sdks/api/policies_api.md). |
| `cache.enabled`, `cache.host`, `cache.port`, `cache.auth.token`, `cache.ssl`, or the same settings as `cache.redis.*`, depending on the release | `CACHE_HOSTNAME`, `CACHE_PORT`, `CACHE_PASSWORD`, and `CACHE_SSL`. Leaving `CACHE_HOSTNAME` unset uses an in-memory cache, which is enough for one instance; several instances need a shared Valkey. There is no setting for a clustered cache or a cache keystore or truststore; `CACHE_SSL` verifies the cache's certificate against the JVM's trust store. See [Cache Settings](settings.md#cache-settings). |
| `metrics.*` | None. Metrics are served in Prometheus format at `/actuator/prometheus` on port 8080, not on a separate port. JMX, Datadog, and CloudWatch reporting are not available. See [Monitoring and Logging](monitoring_and_logging.md). |
| `span.disambiguation.enabled` and the other `span.disambiguation.*` settings | Set per context; see [Span disambiguation](#policy-and-strategy-changes). |

### Policies

The policy format is unchanged from 3.x: a top-level `identifiers` object keyed by filter name, each filter with a `<name>FilterStrategies` array. Existing 3.x policy JSON loads in Philter 4, except for the filter and strategy changes [below](#policy-and-strategy-changes). The visual policy builder has been removed; build policies in the [policy editor](https://policies.philterd.ai/) or by hand.

Philter 4 does not read a policy directory. Upload each policy with the [Policies API](api_and_sdks/api/policies_api.md); a policy is named by the `name` parameter, not by a file name. To upload a 3.x policy directory, naming each policy after its file:

```bash
for f in policies/*.json; do
  name=$(basename "$f" .json)
  curl -k -X POST "https://localhost:8080/api/policies?name=$name" \
    -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
    --data-binary @"$f"
done
```

Each user already has a `default` policy, created from Philter 4's own template, so uploading `default.json` this way returns `409 Conflict`. To use your 3.x default instead, replace it:

```bash
curl -k -X PUT "https://localhost:8080/api/policies/default" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  --data-binary @policies/default.json
```

Policies belong to the user whose API key uploads them. Upload them with a key belonging to the user your clients act as. An administrator can upload them for another user with `owner`, which requires `ADMIN_CROSS_USER_ACCESS_ENABLED=true`.

### Contexts

In 3.x, any value of `c` was accepted, and a request without one used the context `none`. In Philter 4, a context must be created before a request names it: a request naming one that does not exist is refused with `400 Bad Request` (`Context does not exist.`). A request without `c`, or with an empty one, uses no context. Each user has a context named `default`.

Create each context your clients pass with [`POST /api/contexts`](api_and_sdks/api/contexts_api.md#create-a-context), or stop sending `c=none`.

Replacements 3.x made for referential integrity were kept in memory, or in Redis or Valkey, and are not carried over. A value replaced consistently in 3.x gets a new replacement in Philter 4. Philter 4 keeps each context's replacements in its database.

### Changes for clients

* **Every request needs an API key.** Send `Authorization: Bearer <key>` on every request except `GET /api/health`, reading public signing keys, and sign-in. A request without a valid key is refused with `401 Unauthorized`. Each key carries scopes that limit what it may do. Create keys with the [API Keys API](api_and_sdks/api/api_keys_api.md), starting from the bootstrap key. See [API Keys](account/api_keys.md).
* **`/api/status` has been removed.** Use `GET /api/health`, which is unauthenticated. The response format changed: `/api/status` returned the plain string `healthy: <version>`, while `/api/health` returns JSON such as `{"status":"UP","applicationVersion":"...","redactionPolicySchemaVersion":"...","gitCommit":"..."}`, matching the health response shared across Philterd products. Update load-balancer health checks, monitoring probes, and any client that called `/api/status` or matched on `healthy`. See [High Availability](high_availability.md#load-balancing).
* **PDF redaction is asynchronous by default.** `POST /api/filter` with a PDF now returns `202 Accepted` with a `documentId`; download the result from the [Documents API](api_and_sdks/api/documents_api.md). Append `?async=false` to keep the previous synchronous behavior. Text redaction is unchanged.
* **Errors are JSON with a `reason`.** In 3.x an error's body was a plain-text message, such as `An unknown error has occurred.` Every error is now `{"message", "reason"}`, with `field` for an invalid parameter. Match on `reason`, not on `message`, which may change. See [Errors](api_and_sdks/api.md#errors).
* **`POST /api/policies` no longer overwrites.** In 3.x it saved over a policy of the same name. In 4.x it only creates, and a name already in use returns `409 Conflict`. Change scripts that update a policy to [`PUT /api/policies/{name}`](api_and_sdks/api/policies_api.md#replace-a-policy).
* **Deleting a policy returns `204 No Content`** rather than `200 OK`.
* **Listings return an object with a total.** `GET /api/policies` returned a bare JSON array of names. It now returns `{"policies": [...], "total": 12}`, each policy an object with its `name`, `description`, `revision`, `createdAt`, and `updatedAt`, a page at a time (25 by default). See [Listings](api_and_sdks/api.md#listings).
* **Use the Java SDK release for Philter 4.** Clients built on [philter-sdk-java](api_and_sdks/sdks.md) need its 2.x line, which sends API keys and reads the responses above.

### Policy and strategy changes

These affect only policies that use the feature in question. A policy that does not use a given filter or strategy needs no change. For the authoritative format, see the [Policy Schema](policies/policy_schema.md) and [Filter Strategies](policies/filter_strategies.md) reference.

* **`CRYPTO_REPLACE` now uses AES-GCM.** In 3.x this strategy used AES-CBC with the hex-encoded `key` and initialization vector (`iv`) in the top-level `crypto` object. In 4.x it uses authenticated AES-GCM with a random nonce per value. The hex-encoded `key` (64 hex characters for AES-256) is unchanged; the `iv` is no longer used and can be removed. The `key` may be given as `env:VAR_NAME` to read it from an environment variable listed in `POLICY_ALLOWED_ENV_KEYS` instead of storing it in the policy. Values 3.x encrypted with AES-CBC cannot be decrypted by Philter 4's [re-identification](redaction/re-identification.md); decrypt them with the 3.x `key` and `iv`.

    Before (3.x):

    ```json
    { "crypto": { "key": "<hex key>", "iv": "<hex iv>" } }
    ```

    After (4.x):

    ```json
    { "crypto": { "key": "env:CRYPTO_KEY" } }
    ```

* **`FPE_ENCRYPT_REPLACE` no longer needs a key in the policy.** In 3.x a policy using this strategy had to supply a top-level `fpe` object with a `key` and `tweak`, or it failed to load. In 4.x, a policy without one uses a format-preserving-encryption key Philter manages for each user, so the strategy works with no configuration and stays deterministic and reversible for that account. A policy that keeps its `fpe` object uses that key and tweak, and produces the same values it did in 3.x. See [`fpe`](policies/policy_schema.md#fpe).

* **Span disambiguation is now configured per context.** In 3.x, span disambiguation was a global, instance-wide setting (`span.disambiguation.enabled` in `philter.properties`). In 4.x it is enabled on an individual context, with the context's entity type disambiguation option, when you create or edit the context. It is not configured in the policy or through global settings. See [Span Disambiguation](other_features/span_disambiguation.md).
* **A policy can name files only from directories an administrator allows.** In 3.x a policy could name any file for a custom dictionary's `files`, a filter's `ignoredFiles`, the top-level `ignored` `files`, or a `MAP_REPLACE` strategy's `mappingFiles`. In 4.x each must be a regular file inside a directory in `POLICY_FILES_DIRECTORIES`, no larger than `POLICY_FILES_MAX_BYTES`; with `POLICY_FILES_DIRECTORIES` unset, a policy may name no file. Move these files into a directory, set `POLICY_FILES_DIRECTORIES`, and update the paths in your policies, or move the terms into [custom lists](redaction/custom_lists.md). See [Policy files](settings.md#policy-files).
* **A policy can name only environment variables an administrator allows.** In 3.x a `crypto` or `fpe` value of `env:NAME` could name any environment variable. In 4.x it must name one in `POLICY_ALLOWED_ENV_KEYS`; with that unset, a policy may name no variable, and Philter's own secrets can never be listed. List the variables your policies use. See [Environment variables in a policy](settings.md#environment-variables-in-a-policy).
* **A policy can reach only the PhEye and generator services an administrator allows.** A policy's ph-eye `endpoint` must be `PHEYE_ENDPOINT` or one in `PHEYE_ALLOWED_ENDPOINTS`, a generator's `endpoint` must be in `GENERATOR_ALLOWED_ENDPOINTS`, and a policy cannot set a ph-eye `bearerToken`; set the token with `PHEYE_BEARER_TOKEN`. See [Redaction services](settings.md#redaction-services).

## Upgrading from 1.x or 2.x

Philter 2.x was configured and stored its policies as 3.x was, so follow [Upgrading to 4.x](#upgrading-to-4x) directly; there is no need to install 3.x first. Filters have changed since 2.x: check each policy against the [Policy Schema](policies/policy_schema.md) and test it against your own data before relying on it.
