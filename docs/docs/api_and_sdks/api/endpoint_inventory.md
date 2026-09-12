# Endpoint inventory

Philter 4.0 exposes 47 HTTP operations implemented by 49 handlers. The three `/api/filter` handlers select text, PDF, or ZIP via request and response media types. Every operation is listed below; request parameters, bodies, examples, and resource-specific errors are in the linked references and [OpenAPI](../openapi.json).

Send `Authorization: Bearer <api key>` unless the scope is Public. Protected operations reject absent/invalid credentials with 401 and insufficient scope with 403. Account ownership is enforced in addition to scope. Where `owner` is supported, cross-user access requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true`; inaccessible owners return 404.

JSON responses use `application/json`; dates in API response objects use ISO 8601 strings with an offset. Empty responses have no JSON body. Bad parameter values return 400, unsupported request media types 415, incompatible Accept headers 406, and oversized bodies 413. Inspect the response Content-Type before decoding an error; errors may be plain text or a JSON message object.

| Method | Endpoint | Success format | Required scope | Reference |
|--------|----------|----------------|----------------|-----------|
| GET | `/api/audit` | application/json | `audit:read` | [Details](audit_api.md) |
| GET | `/api/contexts` | application/json | `contexts:read` | [Details](contexts_api.md) |
| POST | `/api/contexts` | application/json | `contexts:write` | [Details](contexts_api.md) |
| DELETE | `/api/contexts/{name}` | application/json | `contexts:write` | [Details](contexts_api.md) |
| GET | `/api/contexts/{name}` | application/json | `contexts:read` | [Details](contexts_api.md) |
| PUT | `/api/contexts/{name}` | application/json | `contexts:write` | [Details](contexts_api.md) |
| DELETE | `/api/contexts/{name}/entries` | application/json | `contexts:write` | [Details](contexts_api.md) |
| GET | `/api/contexts/{name}/entries` | application/json | `contexts:read` | [Details](contexts_api.md) |
| GET | `/api/contexts/{name}/entries/export` | application/json | `contexts:read` | [Details](contexts_api.md) |
| POST | `/api/contexts/{name}/entries/import` | application/json | `contexts:write` | [Details](contexts_api.md) |
| DELETE | `/api/contexts/{name}/entries/{entryId}` | application/json | `contexts:write` | [Details](contexts_api.md) |
| GET | `/api/documents` | application/json | `documents:read` | [Details](documents_api.md) |
| DELETE | `/api/documents/{documentId}` | See reference | `documents:write` | [Details](documents_api.md) |
| GET | `/api/documents/{documentId}` | See reference | `documents:read` | [Details](documents_api.md) |
| GET | `/api/documents/{documentId}/status` | application/json | `documents:read` | [Details](documents_api.md) |
| POST | `/api/explain` | application/json | `redact` | [Details](filtering_api.md) |
| POST | `/api/filter` | application/pdf | `redact` | [Details](filtering_api.md) |
| POST | `/api/filter` | application/zip | `redact` | [Details](filtering_api.md) |
| POST | `/api/filter` | text/plain | `redact` | [Details](filtering_api.md) |
| GET | `/api/health` | application/json | `Public` | [Details](public_api.md) |
| GET | `/api/holds` | application/json | `holds:read` | [Details](legal_holds_api.md) |
| POST | `/api/holds` | application/json | `holds:write` | [Details](legal_holds_api.md) |
| DELETE | `/api/holds/{reference}` | application/json | `holds:write` | [Details](legal_holds_api.md) |
| GET | `/api/holds/{reference}` | application/json | `holds:read` | [Details](legal_holds_api.md) |
| DELETE | `/api/ledger` | application/json | `ledger:delete` | [Details](ledger_api.md) |
| GET | `/api/ledger` | application/json | `ledger:read` | [Details](ledger_api.md) |
| DELETE | `/api/ledger/{documentId}` | application/json | `ledger:delete` | [Details](ledger_api.md) |
| GET | `/api/ledger/{documentId}` | application/json | `ledger:read` | [Details](ledger_api.md) |
| GET | `/api/ledger/{documentId}/export` | application/json | `ledger:export` | [Details](ledger_api.md) |
| GET | `/api/ledger/{documentId}/valid` | application/json | `ledger:read` | [Details](ledger_api.md) |
| GET | `/api/lists` | application/json | `lists:read` | [Details](custom_lists_api.md) |
| DELETE | `/api/lists/{name}` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| GET | `/api/lists/{name}` | application/json | `lists:read` | [Details](custom_lists_api.md) |
| POST | `/api/lists/{name}` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| GET | `/api/policies` | application/json | `policies:read` | [Details](policies_api.md) |
| POST | `/api/policies` | See reference | `policies:write` | [Details](policies_api.md) |
| POST | `/api/policies/compile` | application/json | `policies:read` | [Details](policies_api.md) |
| DELETE | `/api/policies/{policyName}` | See reference | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/diff` | application/json | `policies:read` | [Details](policies_api.md) |
| POST | `/api/policies/{policyName}/rollback` | application/json | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/versions` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/versions/{revision}` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/redact-lists` | application/json | `lists:read` | [Details](redact_lists_api.md) |
| POST | `/api/redact-lists` | See reference | `lists:write` | [Details](redact_lists_api.md) |
| PUT | `/api/redact-lists` | See reference | `lists:write` | [Details](redact_lists_api.md) |
| POST | `/api/reidentify` | application/json | `reidentify` | [Details](../../redaction/re-identification.md) |
| GET | `/api/signing-key` | application/json | `Public` | [Details](public_api.md) |
| GET | `/api/signing-key/{keyId}` | application/json | `Public` | [Details](public_api.md) |

PDF async acceptance returns `application/json` with status 202, regardless of the selected download format. A ZIP result contains `redacted.pdf`. Health is a liveness response, not a dependency-readiness probe. Webhooks are outbound notifications; they are documented separately and are not inbound API routes.
