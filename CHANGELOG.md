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
- **Policy diffs show both sides:** each `replace` and `remove` from `GET /api/policies/{name}/diff`
  carries `oldValue`, the value before the change.
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
