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
- **PII counts can be sorted:** `GET /api/pii-counts` takes `sort` (`day`, `context`, or `documents`) and `order`, as the
  other listings do. See [PII Counts API](docs/docs/api_and_sdks/api/pii_counts_api.md).
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
- **Local PhEye models:** a ph-eye filter with `modelPath` runs a GLiNER model in-process through
  ONNX Runtime, with no PhEye service. `modelPath` names a model in `PHEYE_MODELS_DIRECTORY`, which
  `GET /api/models` lists, or is a path in `PHEYE_ALLOWED_MODEL_PATHS`, and
  `PHEYE_ONNX_THREADS` sets its thread count; Philter does not start if that is not a positive whole number.
  `PHEYE_ONNX_DEVICE` can run it on a GPU, with ONNX Runtime's GPU package in place of the CPU package Philter
  includes.
- **Phield and Diffuse integrations,** off by default.
- **Operations:** Prometheus metrics, HTTPS by default, an optional shared Valkey/Redis cache, and
  trusted-proxy handling.

### Changed

- **The default policy detects names with a model inside Philter.** Its person-name filter names
  `ph-eye-pii-en-small` (label `name`, threshold 0.9), which the Docker image includes in
  `PHEYE_MODELS_DIRECTORY` at a pinned revision, so names are redacted with no PhEye service. This applies
  to the `default` template and to users created from now on; an existing user's `default` policy is
  unchanged. The model is CC-BY-4.0 (trained on NVIDIA Nemotron-PII), and its model card, with the attribution
  that license requires, ships beside it. The image is about 620 MB larger. Outside the image, a model by that name must be in
  `PHEYE_MODELS_DIRECTORY`, or redaction with the default policy is refused.
- **PhEye filters examine all of a long text.** A PhEye model reads at most its `max_len` words (512 for the
  `ph-eye-pii-en-*` models) in one call, and a name after that point in a request was not detected, whether the
  model ran in-process or in the PhEye service. The filter now detects in overlapping windows that fit the model.
  See [Long Text](docs/docs/policies/filters/persons_names/persons-names-ner.md#long-text).
- **The OpenAPI specification matches the responses.** The `/api/explain` explanation and its spans, and the signing
  key's `jwk`, have schemas; the audit export is described as CSV text; and responses that have no body, or a
  `{message}` body, are described as such.
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
  `PHEYE_ENDPOINT` or one in `PHEYE_ALLOWED_ENDPOINTS`, sets a ph-eye `bearerToken`, names a `modelPath` that is not
  a model in `PHEYE_MODELS_DIRECTORY` or in `PHEYE_ALLOWED_MODEL_PATHS`, or names a generator `endpoint` not in `GENERATOR_ALLOWED_ENDPOINTS` is refused
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
- **Custom lists are written as a JSON object.** `POST` and `PUT /api/lists/{name}` take
  `{"items": [...], "description": "..."}` rather than a bare array with `description` in the query string, which is
  now refused with `400`. A description can have up to 200 characters, reported as
  `customLists.descriptionMaxLength` by `GET /api/limits`. `GET /api/lists/{name}` returns the items in `items`, as
  they are sent, rather than in `lists`.
- **Creates return `201` and deletes `204`.** `POST /api/contexts` returns `201 Created`. Deleting a context, its
  entries, one entry, a document, a ledger chain, a policy, or releasing a legal hold returns `204 No Content` with no
  body, as deleting a custom list, API key, or webhook already did. The ledger purge and session key revocations still
  return `200` with what they removed.
- **A document's ledger chain is read a page at a time.** `GET /api/ledger/{documentId}` takes `offset` and `limit`
  (25 by default, at most 100) and returns `total`, how many entries the chain has; `valid` still covers the whole
  chain. Export the chain to read every entry at once.
- **Listing totals are 64-bit.** `total` from `GET /api/ledger` and `GET /api/contexts/{name}/entries` is an `int64`
  in the OpenAPI specification, as every other listing's is.
- **A context is created with a JSON body.** `POST /api/contexts` takes `{"name", "entityTypeDisambiguation",
  "disambiguationScope", "ledger"}`, the names `GET` and `PATCH /api/contexts/{name}` use, rather than query
  parameters; a name or setting sent as a query parameter is refused with `400`.
- **A context's settings are changed with `PATCH /api/contexts/{name}` and a JSON body.** `PUT` with the settings in
  the query string is removed. The body takes `entityTypeDisambiguation`, `disambiguationScope`, and `ledger`, the names
  `GET /api/contexts/{name}` returns, and a setting sent as a query parameter is refused with `400`. The `context_updated`
  audit event names the changed settings the same way, such as `entityTypeDisambiguation: true`.
- **Documents are listed in `documents`, each with a `type`.** `GET /api/documents` returns its items in `documents`
  rather than `pendingRedactedDocuments`, since the listing includes finished and failed documents too. Each item, and
  the response from `GET /api/documents/{documentId}/status`, has a `type`; `redaction` is the only type. So does each
  document webhook's payload. A webhook receiver should ignore events it does not recognize, since later releases
  may add some.
- **Policies and contexts are listed as objects.** Each item from `GET /api/policies` has the policy's `name`,
  `description`, `revision`, `createdAt`, and `updatedAt`, and each from `GET /api/contexts` the context's `name` and
  `createdAt`, rather than a bare name; with `all_users`, each also has its `owner`. Read the name from `name`.
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

- **The compose files no longer publish PhEye or MongoDB.** PhEye has no authentication, and the bundled MongoDB's
  root password is in the file; Philter reaches both on the compose network. Only Philter's port 8080 is published.
- **The compose files require `PHILTER_ENCRYPTION_KEY`.** They fell back to a key published in the repository,
  which unwraps every encrypted record. `./compose.sh` generates one in `.env`; without it, `docker compose` stops
  with a message saying so.
- **The generated TLS keystore is readable only by Philter's user.** The Docker image wrote it world-readable, and its
  default password is public, so anyone who could read the volume had the private key.
- **`PHILTER_ENCRYPTION_KEY` is required.** Back it up: data cannot be recovered without it.
- **Policies are encrypted at rest.** A policy's content, every revision of it, and its description and notes are
  encrypted under `PHILTER_ENCRYPTION_KEY`, as list items and ledger tokens are, since a policy can hold the terms it
  redacts, condition values, and keys. Names and content hashes stay readable. See
  [What is encrypted at rest](docs/docs/database.md#what-is-encrypted-at-rest).
- **Only loopback is a trusted proxy by default.** `TRUSTED_PROXIES` defaulted to every private and link-local range,
  so any client on an internal network could set `X-Forwarded-For` and choose the address recorded in the audit log
  and counted by the sign-in rate limit. The default is now `127.0.0.0/8, ::1`. **Behind a load balancer on another
  host, set `TRUSTED_PROXIES` to its addresses**; Philter logs once when it ignores the header. See
  [API Access](docs/docs/settings.md#api-access).
- **PII counts are kept per user.** Counts were bucketed by context name alone, so two users' contexts of one name
  were counted together, and the PII Counts API showed every user's context names to any administrator. Counts are
  now recorded per user's context and day; each entry has a stable `id`, and another user's context name and username
  are returned only with `ADMIN_CROSS_USER_ACCESS_ENABLED`, while their counts still are. An `owner` filter was
  added. See [PII Counts API](docs/docs/api_and_sdks/api/pii_counts_api.md).
- **Failure messages no longer reveal internal details.** A failed document's `error` and its
  `DOCUMENT_REDACTION_FAILED` webhook carried the raw exception message, which could name the model service's host and
  port or a policy snapshot hash; webhook test results and delivery errors carried the HTTP client's message, with the
  addresses a host resolved to. A failure the caller can act on keeps its message, and any other is now reported
  generically or by its kind, with the full error in Philter's log. See
  [Documents API](docs/docs/api_and_sdks/api/documents_api.md) and
  [Delivery errors](docs/docs/api_and_sdks/api/webhooks.md#delivery-errors).
- **Context names are no longer written to the application log.** Creating a context's stored service, deleting a
  context, and evicting an entry to honor `MAX_CONTEXT_SIZE` logged the context's name, which is the user's own label
  and can describe whose data it holds. These messages now name only the owner's user ID, and a request refused for a
  missing scope is logged by its route (such as `/api/contexts/{name}`) rather than its path. The audit log still
  records context names. See [Redaction Contexts](docs/docs/redaction/contexts.md#managing-contexts).
- **Built filters are cached by a SHA-256 hash of the policy.** Every user's redactions share Phileas's filter
  cache, which was keyed by a 64-bit FNV-1a hash of the policy. That hash is not designed to resist deliberately made
  collisions, so a policy could in principle be written to take another user's key and be filtered with that user's
  dictionaries and keys. Phileas 4.5 keys the cache with SHA-256.
- **What Philter stores in Valkey is encrypted and authenticated.** Cached API key records were readable JSON under
  names derived from the key, and Philter trusted them without checking the database, so anything that could write to
  the cache could copy a record to the hash of a key of its choosing and use that key as the record's user, an
  administrator included. Values are now encrypted with AES-256-GCM under a key derived from
  `PHILTER_ENCRYPTION_KEY` and bound to their names, and names are keyed hashes; a value that does not open, or was
  written back after it expired, reads as absent and Philter uses the database. Philter also warns at startup when a
  cache on another host is used without `CACHE_PASSWORD` or `CACHE_SSL`. Existing cache entries are ignored after
  upgrading. See [Protecting the cache](docs/docs/caching.md#protecting-the-cache).
- **A wrong current password counts toward sign-in lockout.** `PUT /api/users/me/password` checked the current
  password without any limit, so a key holding `users:write`, such as a stolen session key, could be used to guess its
  user's password. A wrong current password now counts as a failed sign-in, a locked user cannot change their password
  either (`429`, `reason: locked`), and the lock's audit event names the key that made the guesses. See
  [Users API](docs/docs/api_and_sdks/api/users_api.md#change-your-own-password). The API documentation now also
  says which limits Philter enforces and recommends per-key limits at a proxy for re-identification and ledger
  export. See [Rate limiting](docs/docs/api_and_sdks/api.md#rate-limiting).
- **What an asynchronous redaction keeps is documented and tested.** The Documents API documentation now says what
  Philter keeps for a queued document and for how long, and storage tests on MongoDB and SQLite confirm that the
  submitted and redacted documents are encrypted at rest, that deleting a document removes its record, and that a
  failed job, including one abandoned after its final recovery, keeps no copy of the submitted document. See
  [Documents API](docs/docs/api_and_sdks/api/documents_api.md#what-philter-keeps-and-for-how-long).
- **Filling the cache can no longer lock everyone out of sign-in.** Sign-in counts shared the in-memory cache with
  cached data, and when it was full every username was treated as locked, so one user redacting a document with many
  distinct values could lock all sign-ins. An account's failures and lock are now stored with the user, where no other
  traffic can push them out; usernames with no account and the per-address rate limit use a store of
  their own; IPv6 clients are rate limited per /64; and a full in-memory store drops its entries nearest to expiring
  instead of refusing. See [Lockout and rate limiting](docs/docs/sign_in_security.md#lockout-and-rate-limiting).
- **Tomcat no longer reads `X-Forwarded-For` on Kubernetes.** On a detected cloud platform, Spring Boot trusted the
  header from any private address before `TRUSTED_PROXIES` was consulted. Only `TRUSTED_PROXIES` now decides.
- **Only the user who set a legal hold, or an administrator, can release it.** A user could release a hold an
  administrator placed on their evidence; that is now refused with `403` (`admin_required`). See
  [Legal Holds API](docs/docs/api_and_sdks/api/legal_holds_api.md#release-a-hold).
- **PDFs are bounded before rendering.** A small PDF with a huge page, or a policy with a high `dpi`, could exhaust
  memory. A PDF over `PDF_MAX_PAGES` (default 2000) or with a page over `PDF_MAX_PIXELS_PER_PAGE` (default 50 million
  pixels) is refused with `413`, as is one using an image over that size or a compressed stream that decompresses past
  `PDF_MAX_STREAM_BYTES` (default 256 MB). At most `PDF_MAX_CONCURRENT` PDFs (default 2) are redacted at once per
  instance; a synchronous PDF over that is refused with `503`. The Docker image gives the JVM 75% of the container's
  memory. See [Redaction Engine](docs/docs/settings.md#redaction-engine) and
  [Memory](docs/docs/system_requirements.md#memory).
- **Caller-supplied values cannot forge audit fields.** Names, references, document IDs, usernames, and the
  re-identification reason were written into an event's `details` as is, so a value such as `x, owner: admin` read as
  extra fields. A value that could be misread is now written as a quoted, escaped string. See
  [Auditing](docs/docs/auditing.md#where-audit-events-are-stored).
- **Administrators can clear a sign-in lock.** `POST /api/users/{username}/sign-in/unlock` clears a user's lock
  and failure count before `SIGN_IN_LOCKOUT_MINUTES` ends, audited as `sign_in_unlocked`. Lockout is per username, so
  anyone who can reach sign-in can lock out a known username; [Network placement](docs/docs/sign_in_security.md#network-placement)
  describes restricting who can.
- **Starting MFA enrollment takes the current password.** A stolen session key could enroll an authenticator of its
  own, and the person could then not sign in until an administrator removed it. `POST /api/users/me/mfa` now takes
  `password`, and a wrong one counts toward the sign-in lockout. A user without a password cannot enroll. See
  [Start enrollment](docs/docs/api_and_sdks/api/users_api.md#start-enrollment).
- **Every request body is size-limited.** Only `POST` and `PUT` were, so a `PATCH` body had no limit.
  `MAX_FILE_SIZE_BYTES_OTHER` now applies to every request except a document sent to `/api/filter` or `/api/explain`.
  An oversized JSON body sent to an endpoint that reads it into an object, such as `POST /api/reidentify`, is now a
  `413` rather than a `400`.
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
