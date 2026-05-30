# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the full, narrative history of past versions see [RELEASE_NOTES.md](RELEASE_NOTES.md).

## [4.0.0] - Unreleased

Major release. Rebuilds the UI on Vaadin 25, upgrades the runtime to Spring Boot 4
and Phileas 3.x, and makes PDF redaction asynchronous by default.

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

### Changed

- **PDF redaction is asynchronous by default.** `POST /api/filter` with
  `application/pdf` now responds with `202 Accepted` and a JSON body of the form
  `{"documentId": "..."}` plus a `Location: /api/documents/{documentId}` header.
  Append `?async=false` to keep the previous synchronous response.
- The text endpoint (`text/plain` in / `text/plain` out) is unaffected and remains
  synchronous; the `async` parameter has no effect on text redaction.
- The UI no longer uses the commercial `vaadin-charts` or `vaadin-dashboard`
  components.
- `ContextCache` stores each entry's `ObjectId` with its replacement so cache hits
  increment the read count; legacy values are treated as a miss.
- The context and API-key caches fall back to an in-memory, ephemeral store when
  `CACHE_HOSTNAME` is unset; set it to use Valkey/Redis for a durable, shared cache.

### Security

- **The at-rest encryption key must be supplied via `PHILTER_ENCRYPTION_KEY`**
  (base64-encoded 32-byte AES-256). The built-in default key was removed and Philter
  refuses to start without a valid key. Existing encrypted data is unaffected.

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
