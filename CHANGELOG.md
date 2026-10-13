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
- **Fairness keys on queued documents:** an asynchronous PDF request can pass `fairness_key`, and
  documents one account queues with different keys take turns, as different accounts do. See
  [Fairness keys](docs/docs/api_and_sdks/api/filtering_api.md#fairness-keys).
- **Phield and Diffuse integrations,** off by default.
- **Operations:** Prometheus metrics, HTTPS by default, an optional shared Valkey/Redis cache, and
  trusted-proxy handling.

### Changed

- **Java 25 is required.**
- **No built-in UI.** [Philter UI](https://github.com/philterd/philter-ui), in development, is separate.
- **PDF redaction is asynchronous by default.** Append `?async=false` for the previous behavior.
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
- **Cross-user access is opt-in** through `ADMIN_CROSS_USER_ACCESS_ENABLED`.
- **Ledger exports contain original values** and must be treated as sensitive.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
