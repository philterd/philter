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
- **Asynchronous PDF redaction** with signed webhooks.
- **Phield and Diffuse integrations,** off by default.
- **Operations:** Prometheus metrics, HTTPS by default, an optional shared Valkey/Redis cache, and
  trusted-proxy handling.

### Changed

- **Java 25 is required.**
- **No built-in UI.** [Philter UI](https://github.com/philterd/philter-ui), in development, is separate.
- **PDF redaction is asynchronous by default.** Append `?async=false` for the previous behavior.
- **Outbound HTTPS verifies certificates.** `TLS_TRUST_ALL_ENABLED=true` restores the previous behavior.
- **`POST /api/policies` only creates.** A name in use returns `409`; replace a policy with
  `PUT /api/policies/{name}`.
- **Errors are JSON** with a `message`, plus a `reason` where one status has several causes. Some
  have no body, such as a `404` that would otherwise reveal whether an account exists.
- **Users are deactivated rather than deleted,** keeping their data and ledger evidence.

### Removed

- `GET /api/status` (use `GET /api/health`, which reports `"status": "UP"`) and OpenSearch.

### Security

- **`PHILTER_ENCRYPTION_KEY` is required.** Back it up: data cannot be recovered without it.
- **Cross-user access is opt-in** through `ADMIN_CROSS_USER_ACCESS_ENABLED`.
- **Ledger exports contain original values** and must be treated as sensitive.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
