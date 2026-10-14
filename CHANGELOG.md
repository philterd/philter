# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is the source of truth for 4.0.0 and later: record every release entry here.
[RELEASE_NOTES.md](RELEASE_NOTES.md) holds the narrative history of 3.x and earlier.

## [4.0.0] - Unreleased

Major release, the first since 3.4.0. Philter is now API-only and runs on Java 25, Spring Boot 4, and
Phileas 4.5. See [Upgrading](docs/docs/upgrading.md) for migration steps.

### Added

- **Evidence trail:** a tamper-evident redaction ledger with legal holds, policy versions stamped on
  each redaction, opt-in output signing, and an exportable audit log.
- **Administration over the API,** with scoped API keys and a bootstrap key
  (`PHILTER_BOOTSTRAP_API_KEY`) for the first administrator.
- **Sign-in for a separate user interface:** passwords, expiring session keys, TOTP MFA, and lockout,
  off unless `PASSWORD_SIGN_IN_ENABLED=true`. See [Sign-in Security](docs/docs/sign_in_security.md).
- **SQLite storage, the default:** a single Philter instance keeps all its data in one file
  (`SQLITE_PATH`) with no database server. Set `STORAGE_TYPE=mongodb` for MongoDB, which several
  instances can share. With SQLite, a lock left held by a Philter that stopped part way through an
  operation is cleared at the next start and recorded as `storage_guard_recovered`. See
  [Database](docs/docs/database.md#sqlite) and the
  [SQLite quick start](docs/docs/quick_starts/quick_start_sqlite.md).
- **Moving from SQLite to MongoDB:** `java -jar philter.jar migrate-storage --to mongodb`, run with
  Philter stopped, copies a SQLite database to an empty MongoDB as stored (ids, ledger signatures,
  and encrypted fields unchanged), verifies the copy, and records it as `storage_migrated`. See
  [Moving from SQLite to MongoDB](docs/docs/database.md#moving-from-sqlite-to-mongodb).
- **Limits API:** `GET /api/limits` returns the limits and rules Philter enforces and what the caller
  may do, and `GET /api/sign-in` gives a sign-in page the password rules.
- **Per-list redact list endpoints:** `GET` and `PUT /api/redact-lists/{list}` read and replace the
  always-redact or never-redact list on its own, with `If-Match` on the list's revision so concurrent
  edits are refused rather than lost.
- **Policy templates:** `GET /api/policies/templates/{templateName}` returns a starting point for a new
  policy; `default` is the template new users' default policy is created from. `templates` is now a
  reserved policy name.
- **Webhook test and delivery history:** `POST /api/webhook/test` sends a signed test event and reports
  the outcome, `GET /api/webhook/deliveries` lists deliveries with their status and last error, and
  `PUT /api/webhook` keeps the existing secret when none is sent.
- **Deleted policies and version authors:** `GET /api/policies?deleted=true` lists deleted policies
  whose history is kept, with when and by whom each was deleted, and each policy version names the
  user who made it.
- **Audit actors and user ids:** each event from `GET /api/audit` names its actor's `username`, and
  user responses include the user's `id`.
- **Change a user's email:** `PUT /api/users/{username}/email` lets an administrator set, change, or
  remove a user's email address. Email addresses are now checked for a basic format, including when a
  user is created.
- **Schema versions and automatic migrations:** Philter records the version of its data layout and
  migrates the data at startup when a release needs it, one instance at a time; it refuses to start on
  data written by a newer release. Back up the database before upgrading. See
  [Upgrading](docs/docs/upgrading.md#upgrading-within-4x).
- **Storage selection:** `STORAGE_TYPE` selects where Philter stores its data: `sqlite`, the default,
  or `mongodb`. Philter reaches its data only through storage interfaces, so other storage can be
  added later. Philter refuses to start when `MONGODB_CONNECTION_STRING` is set without
  `STORAGE_TYPE`, rather than guess which storage you meant.
- **Policy diffs show both sides:** each `replace` and `remove` from `GET /api/policies/{name}/diff`
  carries `oldValue`, the value before the change.
- **Filters for ITINs, EINs, and Canadian SINs:** `itin`, `ein`, and `canadaSin` report US Individual
  Taxpayer Identification Numbers, Employer Identification Numbers, and Canadian Social Insurance
  Numbers as their own types. The `ssn` filter reports neither ITINs nor values in the EIN form
  `NN-NNNNNNN`, so a policy, including the managed policies, must enable `ein` to detect EINs. See
  [Filters](docs/docs/policies/filters.md).
- **Asynchronous PDF redaction** with signed webhooks.
- **Client certificates (mutual TLS):** when Philter serves HTTPS itself, `SSL_CLIENT_AUTH=need` with
  `SSL_TRUSTSTORE` requires every client to present a certificate issued by one in the trust store,
  in addition to its API key. See [Client certificates](docs/docs/settings.md#client-certificates-mutual-tls).
- **Fairness keys on queued documents:** an asynchronous PDF request can pass `fairness_key`, and
  documents one account queues with different keys take turns, as different accounts do. See
  [Fairness keys](docs/docs/api_and_sdks/api/filtering_api.md#fairness-keys).
- **The policy behind a queued document:** document status and listing responses include `policyName`,
  `policyVersion`, and `policyContentHash`, and that revision can be read through the policy versions API. See
  [Which policy redacted a document](docs/docs/api_and_sdks/api/documents_api.md#which-policy-redacted-a-document).
- **Phield and Diffuse integrations,** off by default.
- **Operations:** Prometheus metrics, HTTPS by default, an optional shared Valkey/Redis cache, and
  trusted-proxy handling.

### Changed

- **Java 25 is required.**
- **No built-in UI.** [Philter UI](https://github.com/philterd/philter-ui), in development, is separate.
- **PDF redaction is asynchronous by default.** Append `?async=false` for the previous behavior.
- **Policies Phileas cannot load are refused with 400.** A policy is checked by building its filters through
  Phileas when it is created, replaced, copied, or rolled back to, and before a redaction or asynchronous
  submission uses it. A strategy condition that cannot be parsed or that no filter evaluates, a regular
  expression that does not compile or fails its check, or an invalid value such as an unknown `sensitivity`
  returns `400 Bad Request` with Phileas's reason, which never repeats a condition's text. Previously such a
  policy was saved and every redaction with it failed with `500`. See
  [Filter Strategy Conditions](docs/docs/policies/filter_strategies.md#filter-strategy-conditions).
- **A context that does not exist is refused with `400`** (`Context does not exist.`) by synchronous `/api/filter`
  and `/api/explain`, as asynchronous submission already refused it. Previously they returned `500`.
- **A policy cannot direct Philter's requests or credentials.** A policy that names a ph-eye `endpoint` other than
  `PHEYE_ENDPOINT` or one in `PHEYE_ALLOWED_ENDPOINTS`, sets a ph-eye `bearerToken`, names a `modelPath` not in
  `PHEYE_ALLOWED_MODEL_PATHS`, or names a generator `endpoint` not in `GENERATOR_ALLOWED_ENDPOINTS` is refused
  with `400`. Previously any user's policy could send the text being redacted, and the deployment's
  `PHEYE_BEARER_TOKEN`, to a server of its choosing. Requests to these services no longer follow redirects, and
  connecting is bounded at 10 seconds. See [Redaction services](docs/docs/settings.md#redaction-services).
- **A policy can name only files an administrator allows.** A custom dictionary's `files`, a filter's
  `ignoredFiles`, the top-level `ignored` `files`, and a `MAP_REPLACE` strategy's `mappingFiles` must be regular
  files inside `POLICY_FILES_DIRECTORIES` and no larger than `POLICY_FILES_MAX_BYTES` (default 10 MB). Unset, a
  policy may name no file. A policy naming any other file is refused with `400`. Previously any user's policy could
  read any file the server could read, and a device such as `/dev/zero` could exhaust its memory. See
  [Policy files](docs/docs/settings.md#policy-files).
- **A policy can name only environment variables an administrator allows.** A `crypto` or `fpe` key or tweak given
  as `env:NAME` must name a variable in `POLICY_ALLOWED_ENV_KEYS`; unset, a policy may name no variable, and Philter's
  own secrets can never be listed. A policy naming any other variable is refused with `400`, including by
  `/api/reidentify`. Previously any user's policy could use any variable in Philter's environment as its key,
  including a key meant for other policies or one of Philter's own secrets. See
  [Environment variables in a policy](docs/docs/settings.md#environment-variables-in-a-policy).
- **`token !=` conditions apply.** A strategy with a condition such as `token != "127.0.0.1"` was
  never applied before; it now applies to every value other than the one named, ignoring case. Review
  policies that use `token !=`. See [Filter Strategy Conditions](docs/docs/policies/filter_strategies.md#filter-strategy-conditions).
- **Outbound HTTPS verifies certificates.** `TLS_TRUST_ALL_ENABLED=true` restores the previous behavior.
- **`POST /api/policies` only creates.** A name in use returns `409`; replace a policy with
  `PUT /api/policies/{name}`.
- **Errors are JSON** with a `message` and a `reason` code, and a `field` when an invalid parameter is
  known, from every refusal, including authentication, scope checks, and the audit export. A `404`
  that would otherwise reveal whether an account exists says only `not_found`, the same as any other.
- **Users are deactivated rather than deleted,** keeping their data and ledger evidence.
- **Listings return an object with `total`,** and accept `sort`, `order`, and, where it applies, a `q`
  search. `GET /api/policies`, `/versions`, `/api/holds`, and `/api/lists` returned bare arrays;
  `GET /api/lists` is now paged. `GET /api/users` filters by `role` and `active`, `GET /api/documents`
  by `status`, and the ledger's `all_users` listing accepts `q`.
- **Lists, contexts, and holds are deleted by path only.** `DELETE /api/lists?name=`,
  `DELETE /api/contexts?name=`, and `DELETE /api/holds?reference=` are removed: names are checked when
  they are created, so `DELETE /api/lists/{name}`, `/api/contexts/{name}`, and `/api/holds/{reference}`
  always reach them.
- **Request ids:** every `/api` response returns `X-Request-Id`, and the audit events the request causes
  record the same id, so an error a client sees can be found in the audit log. Redaction events keep
  using the document id, so a queued redaction's start and completion share one id.
- **Manage your own sessions:** session key listings show each session's client address and user agent
  (kept only while the session lasts) and mark the current one, and `DELETE /api/users/me/session-keys`
  signs out every other session.
- **Stable audit log export paging:** `GET /api/audit/export` returns `X-Philter-Export-Next-Cursor`
  and accepts `cursor`, which continues after the last event of the page before. Paging by cursor
  returns each event once even while events are being written, including each page's own
  `audit_log_exported` event. `offset` still works but can repeat events on a range that includes the
  current day.
- **No lost updates on policy edits:** policy reads return an `ETag` covering the policy and its
  description and notes. Send it in `If-Match` on `PUT /api/policies/{name}`,
  `PUT /api/policies/{name}/details`, or `POST /api/policies/{name}/rollback`, and the write is refused
  with `409` and reason `policy_changed` if the policy changed since. Requests without `If-Match` work
  as before. With `details=true`, `POST` and `PUT /api/policies` take the description and notes in the
  same body as the policy, written together.

### Removed

- `GET /api/status` (use `GET /api/health`, which reports `"status": "UP"`) and OpenSearch.

### Security

- **`PHILTER_ENCRYPTION_KEY` is required.** Back it up: data cannot be recovered without it.
- **Policies are encrypted at rest.** A policy's content, every revision of it, and its description and notes are
  encrypted under `PHILTER_ENCRYPTION_KEY`, as list items and ledger tokens are, since a policy can hold the terms it
  redacts, condition values, and keys. Names and content hashes stay readable. See
  [What is encrypted at rest](docs/docs/database.md#what-is-encrypted-at-rest).
- **Webhooks refuse every special-purpose address.** Without an allowlist, a webhook could reach shared address
  space (100.64/10, which holds Alibaba Cloud's metadata service at 100.100.100.200), reserved and benchmarking
  ranges, and private IPv4 addresses through NAT64, 6to4, or IPv4-compatible IPv6 forms. These are now refused
  unless an administrator lists them; NAT64 and 6to4 are judged by the IPv4 address they lead to, so public endpoints
  still work. See [Where a webhook may point](docs/docs/api_and_sdks/api/webhooks.md#where-a-webhook-may-point).
- **Policy and redact list changes apply to the next redaction.** A change to the always/never redact lists used
  to take up to `REDACTION_CACHE_TTL_SECONDS` (60 seconds) to apply, so a term added to always-redact, or removed
  from never-redact, kept being let through; a policy edit did the same on instances other than the one that made
  it. Redaction now uses its cached copy only while the stored revisions match, on every instance. See
  [Caching](docs/docs/caching.md).
- **Context token hashes are keyed per user.** Each user's context tokens are hashed under a key derived from
  `PHILTER_ENCRYPTION_KEY` and their username, so one user cannot match another's exported hashes by redacting
  guesses in a context of their own, or import another's export to learn what its replacements stand for. An export
  now matches only in contexts of the same username under the same key: within a deployment, or carried to another
  with the same key and username, such as from staging to production. See
  [Exporting and Importing Mappings](docs/docs/redaction/contexts.md#exporting-and-importing-mappings).
- **A queued document's captured configuration is kept only with the job.** It holds the resolved policy,
  list contents, and keys, is encrypted, and is removed when the job expires or is deleted; no other copy is kept.
- **Cross-user access is opt-in** through `ADMIN_CROSS_USER_ACCESS_ENABLED`. While it is off, an administrator can
  create an API key for another user only as that user's first key, so the setting cannot be bypassed by creating
  a key for a user who already holds data and acting as them. With it on, such a key and every read of another
  user's policies, custom lists, documents, and ledger through `owner` are recorded as `admin_cross_user_access`;
  before, only changes and some reads were.
- **Ledger exports contain original values** and must be treated as sensitive.
- **Access is decided by the path a request is routed by.** Authentication, scope checks, request size limits,
  and content verification all use the decoded path, so a percent-encoded path such as `/%61pi/documents` is
  treated exactly as `/api/documents`. Before this, such a path reached the endpoint without the scope check or
  the live checks on the key, so a key could use endpoints outside its scopes and a deactivated user's key or a
  restricted session key kept working.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
