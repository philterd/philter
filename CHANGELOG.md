# Changelog

All notable changes to Philter are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file is the source of truth for 4.0.0 and later: record every release entry here.
[RELEASE_NOTES.md](RELEASE_NOTES.md) holds the narrative history of 3.x and earlier.

## [4.0.0] - Unreleased

Major release, the first since 3.4.0. Philter is now API-only and runs on Java 25, Spring Boot 4, and
Phileas 4.5. Redaction gains an evidence trail, the whole deployment can be administered over the
API, and people can sign in through a separate user interface. See [Upgrading](docs/docs/upgrading.md)
for migration steps.

### Added

- **Evidence trail.** A tamper-evident redaction ledger with legal holds, policy versioning that
  stamps each redaction with the policy version applied, opt-in output signing, and an audit log of
  security-relevant actions with CSV export.
- **Administration over the API.** Endpoints for users, API keys, webhooks, admin settings, policy
  details and managed policies, contexts, custom lists, redact lists, re-identification, and PhiSQL
  compilation. Administrators can list across all users and, when enabled, act on another user's
  resources.
- **API key scopes.** Every key carries scopes naming what it may do, changeable without replacing
  the key. A key cannot grant or manage scopes it does not hold.
- **Bootstrap API key.** `PHILTER_BOOTSTRAP_API_KEY` provides the first administrator credential.
- **Sign-in for user interfaces.** Optional passwords, expiring session keys, password sign-in, TOTP
  multi-factor authentication, and sign-in lockout and rate limiting. Sign-in is off unless
  `PASSWORD_SIGN_IN_ENABLED=true`. See [Sign-in Security](docs/docs/sign_in_security.md).
- **Asynchronous PDF redaction** with job endpoints and signed webhook delivery.
- **Phield and Diffuse integrations** for PII drift monitoring and differential-privacy reporting,
  both off by default.
- **Operations.** Prometheus metrics, HTTPS by default with a generated self-signed certificate,
  optional shared Valkey/Redis caching for multi-instance deployments, and trusted-proxy handling of
  `X-Forwarded-For`.

### Changed

- **Java 25 is required.**
- **No built-in UI.** Philter is administered through its API. [Philter UI](https://github.com/philterd/philter-ui),
  in development, is a separate application.
- **PDF redaction is asynchronous by default.** Append `?async=false` for the previous behavior.
- **`/api/health` is the only health endpoint** and reports `"status": "UP"`.
- **Errors are JSON.** Every error response is a JSON object with a `message` field, whatever the
  request's `Accept` header asked for; errors from the exception handler were plain text. The one
  exception is the `403` from `GET /api/audit/export` for a caller who is not an administrator.
- **Outbound HTTPS verifies certificates.** Trust a private issuer in the JVM truststore, or set
  `TLS_TRUST_ALL_ENABLED=true`.
- **Context names are unique per user**, and **users are deactivated rather than deleted**, so their
  data and ledger evidence are kept.
- **`PUT /api/contexts/{name}` changes only the settings given**, rather than resetting an omitted one
  to `false`. `GET /api/contexts/{name}` returns both settings.

### Removed

- `GET /api/status` (use `GET /api/health`) and the OpenSearch dependency with its settings.

### Security

- **`PHILTER_ENCRYPTION_KEY` is required.** Each record is encrypted with its own data key, wrapped
  by this key. Back it up: data cannot be recovered if it is lost or changed.
- **Cross-user access is opt-in** through `ADMIN_CROSS_USER_ACCESS_ENABLED`, off by default.
- **Ledger exports contain original values** and must be treated as sensitive.

[4.0.0]: https://github.com/philterd/philter/releases/tag/4.0.0
