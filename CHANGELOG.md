# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is the source of truth for 4.0.0 and later: record every release entry here.
[RELEASE_NOTES.md](RELEASE_NOTES.md) holds the narrative history of 3.x and earlier.

## [4.0.0] - Unreleased

Major release, the first since 3.4.0. Philter is API-only, with no built-in UI; the runtime moves to
Java 25, Spring Boot 4, and Phileas 4.5, and redaction gains an evidence trail: a tamper-evident
ledger, policy versioning, output signing, and an audit log.

See [Upgrading](docs/docs/upgrading.md) for migration steps.

### Added

- **Redaction ledger.** A tamper-evident, hash-chained record of every redaction made in a
  ledger-enabled context, with endpoints under `/api/ledger`, legal holds, and
  JSON export. Deletion is administrator-only and off unless `LEDGER_DELETION_ENABLED=true`.
- **Policy versioning.** Every policy save is retained as an immutable, content-addressed snapshot,
  and each redaction is stamped with the policy name, version, and content hash in the ledger, the
  `/api/filter` response headers, and `/api/explain`.
- **Output signing.** Text filter and explain responses can be signed with an ES256 JWT returned in
  `X-Philter-Signature`, binding the response hash and the applied policy. Opt-in.
- **Legal holds.** Named, audited holds that block deletion of redaction evidence until released,
  scoped to one document chain or to all of a user's evidence, with `/api/holds`.
- **Audit log.** Security-relevant actions are recorded to a new `audit_events` collection, readable
  and exportable as CSV over the API.
- **Phield and Diffuse integrations.** Optional publishing of PII type counts for drift monitoring
  and of differential-privacy aggregates. Both are configured with `/api/settings` and off by default.
  The Phield integration takes an optional API key, sent as a bearer token, for Phield instances
  run with `PHIELD_API_KEY` set. The key is encrypted at rest.
- **Asynchronous PDF redaction** and the `/api/documents` endpoints for listing, polling,
  downloading, and deleting jobs, with signed webhook delivery on completion or failure.
- **Admin cross-user access.** Administrators can act on another user's resources with an `owner`
  parameter.
- **Prometheus metrics** at `/actuator/prometheus`, replacing the in-application metrics dashboard.
- **HTTPS by default.** The Docker image generates a self-signed certificate on first start.
- **Bootstrap API key.** `PHILTER_BOOTSTRAP_API_KEY` seeds the admin user's first API key. It is
  required at startup until the admin has had an API key, and ignored afterwards.
- **Users API.** Endpoints under `/api/users` list, read, and create users, set a user's role,
  deactivate and reactivate users, and mint API keys for a user, so a deployment can be administered
  over the API. `GET /api/users/me` returns the calling key's user. The endpoints require an administrator and
  the new `users:read` scope or `users:write`/`api-keys:write`, refuse to demote or deactivate the
  last active administrator, and cannot grant a key a scope the calling key does not hold. Every
  change is audited with the acting administrator and API key.
- **User passwords.** A user can have an optional password, stored as a bcrypt hash, for a person who
  signs in through a user interface; users without one authenticate with API keys only. It is set at
  creation (`POST /api/users`), changed by the user with the current one (`PUT /api/users/me/password`),
  or set and reset by an administrator (`PUT /api/users/{username}/password`), which requires a change
  at next sign-in. Passwords must be 16 characters to 72 UTF-8 bytes. A change or reset revokes the
  user's session keys. The `admin` user's first password is set with the bootstrap API key. Audited
  as `user_password_set`, `user_password_changed`, and `user_password_reset`, never with the password.
- **Session keys.** API keys for a person who signs in through a user interface, which expire after
  `SESSION_KEY_IDLE_TIMEOUT_MINUTES` without a request (default 30) or `SESSION_KEY_MAX_LIFETIME_MINUTES`
  after issue (default 720), and are checked against the database on every request so expiry and
  revocation apply on every instance at once. The holder signs out with `DELETE /api/api-keys/current`,
  which any key may call; an administrator revokes a user's session keys with
  `DELETE /api/users/{username}/session-keys`. Listings mark session keys and give their expiry.
  Expiry is audited as `api_key_expired`. Long-lived keys are unchanged.
- **Password sign-in.** `POST /api/sign-in` exchanges a username and password for a session key,
  for a user interface such as Philter UI. Disabled unless `PASSWORD_SIGN_IN_ENABLED=true`, when it
  returns 404. Every kind of failure gets the same 401 in about the same time. The session key holds
  every scope, with the user's role deciding administrator access, but cannot create API keys. A user
  who must change their password gets a key that can only do that and sign out. Audited as
  `sign_in_succeeded` and `sign_in_failed` with the username and client IP address.
- **API Keys API.** `/api/api-keys` lists, creates, re-scopes, and revokes the calling key's user's
  keys, so a key can be rotated without an administrator; `GET /api/users/{username}/api-keys` lets
  an administrator list another user's keys and manage them by ID. A key cannot grant a scope it does
  not hold, change or revoke a key holding a scope it does not hold, or revoke itself. New
  `api-keys:read` scope. Changes are audited with the acting user and API key.
- **Webhook API.** `GET`, `PUT`, and `DELETE /api/webhook` read, set, and remove the user's webhook
  for asynchronous results, with `owner` for administrators under the cross-user rules. The URL and
  secret are validated before they are saved. New `webhooks:read` and
  `webhooks:write` scopes.
- **Context counts by filter type.** `GET /api/contexts/{name}` returns the context's entries
  counted by filter type (`filterTypes`, plus `untyped`) alongside `size`, computed in the database.
  The counts now include entries with UUID replacements, so they sum to `size`.
- **Audit log CSV export over the API.** `GET /api/audit/export` returns the audit log for a date
  range as CSV, one page at a time (`limit`, default 100, at most 1,000; `offset`), with an optional
  `zone` for the range and response headers giving the row count, whether more remain, the next
  offset, and the zone used. Each export is audited as `audit_log_exported`. A cell that a spreadsheet would run as a formula is prefixed with an apostrophe.
- **Listings across all users.** `all_users=true` on `GET /api/policies`, `/api/contexts`,
  `/api/lists`, `/api/ledger`, and `/api/holds` lists every user's resources, each naming its owner,
  for administrators with `ADMIN_CROSS_USER_ACCESS_ENABLED`. Per-user responses are unchanged.
- **Settings API.** `GET` and `PATCH /api/settings` read and change differential-privacy count
  recording, output signing, the webhook destination allowlist, and Phield publishing, with new
  `settings:read` and `settings:write` scopes. Values are validated before anything is saved, and
  changes are audited by setting name and calling API key.
- **Policy details, managed policies, and copying over the API.** `GET` and `PUT
  /api/policies/{name}/details` read and set a policy's description and notes, which `POST
  /api/policies` also accepts. `GET /api/policies?managed=true` lists the managed policies, which are
  read by name like any other, and `POST /api/policies/{name}/copy` copies a managed or own policy.
- **Trusted proxies.** For API requests, the client IP address recorded in the audit log comes from `X-Forwarded-For`
  only when the request arrives from a trusted proxy (`TRUSTED_PROXIES`, defaulting to private and
  loopback ranges), read from the right so a client cannot choose it, and only when it is an IP
  address. Otherwise the connection's address is recorded.
- **Ledger index for chain heads across users.** Listing and counting every user's ledger chains
  uses an index instead of scanning and sorting the whole ledger.
- **New API endpoints.** Management APIs for contexts (including entry paging, export, and import),
  custom lists, and always/never redact lists; `POST /api/reidentify` to reverse a `CRYPTO_REPLACE`
  or `FPE_ENCRYPT_REPLACE` value, which requires a reason that is recorded in the audit log;
  `POST /api/policies/compile` to compile PhiSQL into a native policy; the policy version endpoints
  (`versions`, `versions/{revision}`, `diff`, `rollback`); and `GET /api/signing-key`.
- **API key scopes.** Every API key carries a set of scopes naming what it may do, chosen when the key
  is created and changeable afterwards without replacing the key. A request to an endpoint whose scope
  the key does not hold is refused with `403 Forbidden` naming the missing scope. `ledger:export` and
  `reidentify` are separate scopes from the resources they belong to, because they are the two
  capabilities that return original values in the clear, so a key can read a ledger without being able
  to export its plaintext. Scopes only narrow a key: admin-only operations and cross-user access still
  require the role and `ADMIN_CROSS_USER_ACCESS_ENABLED` on top. Scope changes are audited.
- Optional shared Valkey/Redis caching, and bounded context and vector storage.

### Changed

- **Java 25 is required.**
- **PDF redaction is asynchronous by default.** `POST /api/filter` with `application/pdf` returns
  `202 Accepted` and `{"documentId": "..."}`; append `?async=false` for the previous behavior. Text
  redaction is unchanged and remains synchronous.
- **`/api/health` is the only health endpoint, and returns `"status": "UP"`** instead of
  `"Healthy"`, matching the health response shared across Philterd products. Its response shape also
  changed to the one `/api/status` used to return. Update health probes to `GET /api/health` and to
  match on `UP`.
- **Philter has no built-in UI.** It is administered through its API. [Philter UI](https://github.com/philterd/philter-ui),
  a separate application in development, is planned to run against Philter with an administrator's API key.
- **Outbound HTTPS from the redaction pipeline now verifies certificates.** Earlier builds trusted
  any certificate from any host unconditionally. If Philter reaches ph-eye (or another service in the
  pipeline) over HTTPS with a self-signed or privately-issued certificate, add its issuer to the JVM
  truststore, or set `TLS_TRUST_ALL_ENABLED=true` to restore the old behavior. Philter logs a warning
  at startup while that switch is on.
- **Context names are unique per user** rather than globally.
- **Users are deactivated rather than deleted**, so their policies and ledger evidence are preserved.

### Removed

- **`GET /api/status` was removed.** Use `GET /api/health`, which returns the same response.
- **OpenSearch** is no longer a dependency. The `opensearch` service and the `OPENSEARCH_*` and
  `API_REQUESTS_INDEXING_ENABLED` variables were removed.

### Security

- **`PHILTER_ENCRYPTION_KEY` is now required.** The built-in default key was removed and Philter
  refuses to start without a valid base64-encoded 32-byte key. Each record is encrypted with its own
  data key, stored wrapped under the master key rather than beside the data in the clear. Back the
  key up: data encrypted with it cannot be recovered if it is lost or changed.
- **Admin cross-user access is opt-in**, gated by `ADMIN_CROSS_USER_ACCESS_ENABLED` (`false` by
  default). Requests naming another `owner` without authorization return `404`, never `403`, so the
  API does not reveal whether a user or resource exists.
- **Ledger exports contain decrypted tokens and replacements** and must be treated as sensitive.
  Audit events never include those values, and ledger searches are audited by a hash of the search
  term rather than the term itself.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
