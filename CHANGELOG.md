# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the full, narrative history of past versions see [RELEASE_NOTES.md](RELEASE_NOTES.md).

## [4.0.0] - Unreleased

Major release. Rebuilds the UI on Vaadin 25, upgrades the runtime to Spring Boot 4
and Phileas 3.4.0, and makes PDF redaction asynchronous by default.

### Added

- **Asynchronous PDF redaction.** Submitted PDFs are queued in a new
  `pending_documents` collection and processed by an in-process worker, with
  multi-instance coordination and automatic crash recovery.
- **`/api/documents` endpoints** for managing asynchronous redactions:
  - `GET /api/documents` — list submissions for the calling API key.
  - `GET /api/documents/{documentId}/status` — status only.
  - `GET /api/documents/{documentId}` — download the redacted bytes (`200`),
    `409 Conflict` if still processing, `410 Gone` if redaction failed.
  - `DELETE /api/documents/{documentId}` — remove a record.
- **TTL on pending documents.** A MongoDB TTL index on `completed_at` deletes
  finished records after a configurable interval (default 7 days, override with
  `PENDING_DOCUMENTS_TTL_SECONDS`).
- **Dashboard PDF redaction**, run synchronously with an immediate browser download.
- **Webhook delivery for async redactions.** A signed (HMAC-SHA256) HTTP POST is
  sent when an async redaction completes or fails, with retries. Configured per user
  via `webhook_url` and `webhook_secret`.
- **New context API endpoints:** `PUT /api/contexts/{name}` (update `coref` and
  `disambiguation`), `GET /api/contexts/{name}/entries` (paginated), and
  `DELETE` variants for emptying entries or removing a single entry.
- **Bounded context and vector storage.** `MAX_CONTEXT_SIZE` (default 10,000)
  enforces least-read eviction of context entries; `MAX_VECTORS_PER_CONTEXT`
  (default 100,000) enforces FIFO eviction of vectors.
- **Prometheus metrics.** Redaction, token, and API-request counters are exposed
  at `/actuator/prometheus` (`philter_redactions_total`, `philter_tokens_total`,
  `philter_api_requests_total`) for scraping by an external observability stack.
- **`GET /api/status`** reports the Philter version, git commit, and the redaction
  policy schema version supported by the bundled Phileas. It is served by a
  controller and appears in the OpenAPI spec and Swagger UI.
- **Policy editor link.** The Policies view links to the hosted redaction policy
  editor at `https://policies.philterd.ai` (new tab), passing the supported schema
  version as a `?version=` query parameter.
- **Redaction Ledger.** The tamper-evident, per-document ledger is now exposed end to end:
  - REST endpoints under `/api/ledger` — `GET /api/ledger` (paginated chain heads),
    `GET /api/ledger/{documentId}` (a document's chain), `GET /api/ledger/{documentId}/valid`
    (verify the hash chain), `GET /api/ledger/{documentId}/export` (export decrypted entries),
    and `DELETE` for a single document's chain or all of the caller's chains.
  - A **Redaction Ledger** view in the UI for browsing, searching, exporting, and purging.
  - Retention via `REDACTION_LEDGER_TTL_SECONDS` — entries are kept **indefinitely by default**
    (`0`); set a positive value for MongoDB TTL expiry. Ledger entries are also cleaned up when a
    document chain is deleted, on a manual purge, and on user deletion.
- **Admin cross-user access.** Administrators may view and act on another user's contexts,
  policies, custom lists, documents, and redaction ledger by passing an `owner=<email>` query
  parameter on those API endpoints, and via admin-only "All …" tabs in the UI. Cross-user actions
  are audited as `admin_cross_user_access`.
- **Audit log viewer and export.** A new `audit_events` collection records security-relevant
  actions; the admin UI adds an **Audit Log** tab that exports events to CSV over a chosen date
  range (max 30-day window, server time zone). New audit events include `admin_cross_user_access`
  and `redaction_ledger_exported`.
- **`GET /api/contexts` is now paginated** with `offset`/`limit` query parameters (default `0`/`25`),
  matching `GET /api/policies`.
- **A `default` context is created automatically** for each new user.
- **Optional shared caching of contexts and API keys.** Set `CACHE_HOSTNAME` to use a
  Valkey/Redis cache shared across instances; when unset, an in-memory, ephemeral cache is
  used.
- **My Account, Always/Never Redact Lists, and SDKs views.** Account settings (email, API keys,
  webhook) are consolidated under a **My Account** page; the per-account always-redact / never-redact
  lists move to a dedicated **Always/Never Redact Lists** page; and SDK references move to an **SDKs**
  page. The side navigation is grouped into Redaction, Account, and Administration sections.

### Changed

- **PDF redaction is asynchronous by default.** `POST /api/filter` with
  `application/pdf` now responds with `202 Accepted` and a JSON body of the form
  `{"documentId": "..."}` plus a `Location: /api/documents/{documentId}` header.
  Append `?async=false` to keep the previous synchronous response.
- The text endpoint (`text/plain` in / `text/plain` out) is unaffected and remains
  synchronous; the `async` parameter has no effect on text redaction.
- **`/api/health` response shape changed** to match `/api/status`:
  `{"status":"Healthy","applicationVersion":"...","redactionPolicySchemaVersion":"...","gitCommit":"..."}`.
  The previous `{"health":"ok","git-commit":"..."}` body no longer applies. Both
  endpoints remain unauthenticated. Update any health probes that parsed the old shape.
- **Docker Compose simplified.** Philter now serves its own UI, so the separate
  `philter-ui` container is gone; the `philter` service publishes its port (`8080:8080`)
  directly. The `opensearch` service was also removed (see Removed).
- **Context names are now unique per user** rather than globally, so different users may reuse the
  same context name. API endpoints that target another user's context accept an `owner` parameter to
  disambiguate.
- **Filter and explain requests accept an empty or null context name.** When no context is supplied,
  no context features are applied and disambiguation is scoped to the submitted document only.
- **Deleting a user now cascades** to that user's contexts, context entries, cached values, and
  redaction ledger; deleting a context deletes its context-entry documents.
- **The build targets Java 25.**

### Security

- **The at-rest encryption key must be supplied via `PHILTER_ENCRYPTION_KEY`**
  (base64-encoded 32-byte AES-256). The built-in default key was removed and Philter
  refuses to start without a valid key. Existing encrypted data is unaffected.
- **Admin cross-user access is opt-in and disabled by default.** It is gated by the
  `ADMIN_CROSS_USER_ACCESS_ENABLED` kill switch (`false` by default); while disabled, an admin sees
  only their own data like any user. Requests that name another `owner` without authorization return
  `404 Not Found` (never `403`) so the API does not reveal whether a user or resource exists.
- **Redaction ledger exports contain decrypted tokens and replacements** and must be treated as
  sensitive; audit events never include those values, and ledger searches are audited by a hash of the
  search term rather than the term itself.

### Removed

- **OpenSearch** is no longer a dependency; usage metrics moved to Prometheus (see
  Added). The `opensearch` service and the `OPENSEARCH_*` /
  `API_REQUESTS_INDEXING_ENABLED` variables were removed.
- **The in-application Metrics dashboard** was removed in favor of Prometheus metrics.

### Fixed

- Context lookups by name (`ContextDataService.findOneByNameAndUserId`) queried the
  wrong field (`name` instead of `context_name`) and never matched, causing
  context-based redaction to fail.
- The PDF redaction endpoints attributed work to the API key's id instead of the
  owning user's id, breaking user scoping and webhook delivery.
- The PDF-to-ZIP endpoint passed the wrong input MIME type (`IMAGE_JPEG` instead of
  `APPLICATION_PDF`).
- `MongoVectorService.hashAndInsert` now stores `user_id`, so
  `getVectorRepresentation` matches what was written.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
