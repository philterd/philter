# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is the source of truth for 4.0.0 and later: record every release entry here.
[RELEASE_NOTES.md](RELEASE_NOTES.md) holds the narrative history of 3.x and earlier.

## [4.0.0] - Unreleased

Major release, the first since 3.4.0. The UI is rebuilt on Vaadin 25 and served by Philter itself,
the runtime moves to Java 25, Spring Boot 4, and Phileas 4.2.0, and redaction gains an evidence
trail: a tamper-evident ledger, policy versioning, output signing, and an audit log.

See [Upgrading](docs/docs/upgrading.md) for migration steps.

### Added

- **Redaction ledger.** A tamper-evident, hash-chained record of every redaction made in a
  ledger-enabled context, with endpoints under `/api/ledger`, a dashboard view, legal holds, and
  JSON export. Deletion is administrator-only and off unless `LEDGER_DELETION_ENABLED=true`.
- **Policy versioning.** Every policy save is retained as an immutable, content-addressed snapshot,
  and each redaction is stamped with the policy name, version, and content hash in the ledger, the
  `/api/filter` response headers, and `/api/explain`.
- **Output signing.** Text filter and explain responses can be signed with an ES256 JWT returned in
  `X-Philter-Signature`, binding the response hash and the applied policy. Opt-in.
- **Audit log.** Security-relevant actions are recorded to a new `audit_events` collection, with an
  admin viewer and CSV export.
- **Asynchronous PDF redaction** and the `/api/documents` endpoints for listing, polling,
  downloading, and deleting jobs, with signed webhook delivery on completion or failure.
- **Admin cross-user access.** Administrators can act on another user's resources with an `owner`
  parameter and admin dashboard tabs.
- **Prometheus metrics** at `/actuator/prometheus`, replacing the in-application metrics dashboard.
- **HTTPS by default.** The Docker image generates a self-signed certificate on first start.
- **Bootstrap API key.** `PHILTER_BOOTSTRAP_API_KEY` seeds a credential for automation and turnkey
  deployments without using the dashboard.
- Optional shared Valkey/Redis caching, an API IP allowlist, bounded context and vector storage,
  paginated contexts, and `GET /api/status`.

### Changed

- **Java 25 is required.**
- **PDF redaction is asynchronous by default.** `POST /api/filter` with `application/pdf` returns
  `202 Accepted` and `{"documentId": "..."}`; append `?async=false` for the previous behavior. Text
  redaction is unchanged and remains synchronous.
- **`/api/health` returns a new response shape**, matching `/api/status`. Update health probes.
- **Philter serves its own UI**, so the separate `philter-ui` container is gone.
- **Context names are unique per user** rather than globally.
- **Users are deactivated rather than deleted**, so their policies and ledger evidence are preserved.

### Removed

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
