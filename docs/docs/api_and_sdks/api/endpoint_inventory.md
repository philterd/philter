# Endpoint inventory

Every HTTP operation Philter 4.0 exposes is listed below. The three `/api/filter` rows are one operation served by three handlers, selected by the request and response media types; request parameters, bodies, examples, and resource-specific errors are in the linked references and [OpenAPI](../openapi.json).

Send `Authorization: Bearer <api key>` unless the scope is Public. "Any key" accepts any valid key, whatever its scopes. Protected operations reject absent/invalid credentials with 401 and insufficient scope with 403. Account ownership is enforced in addition to scope. Where `owner` is supported, cross-user access requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true`; inaccessible owners return 404.

JSON responses use `application/json`; dates in API response objects use ISO 8601 strings with an offset. Empty responses have no JSON body. Bad parameter values return 400, unsupported request media types 415, incompatible Accept headers 406, and oversized bodies 413. An error response that has a body is a JSON object whose `message` field explains the error, whatever the request's `Accept` header asked for. That includes a request refused before it reaches an endpoint because its path is malformed or contains a character or segment that is not allowed, such as an encoded `/`, `\`, or NUL, a `;` or encoded `%`, an empty segment (`//`), or a `.` or `..` segment: it returns `400`. So does a request whose headers, including the request line, exceed the server's size limit. The exception is a `403` from `GET /api/audit/export` for a caller who is not an administrator, which is plain text. Some 404s have no body, such as one for an owner that does not exist or cannot be reached.

| Method | Endpoint | Success format | Required scope | Reference |
|--------|----------|----------------|----------------|-----------|
| GET | `/api/api-keys` | application/json | `api-keys:read` | [Details](api_keys_api.md) |
| POST | `/api/api-keys` | application/json | `api-keys:write` | [Details](api_keys_api.md) |
| GET | `/api/api-keys/scopes` | application/json | Any key | [Details](api_keys_api.md#list-the-scopes) |
| DELETE | `/api/api-keys/current` | See reference | Any key | [Details](api_keys_api.md#sign-out) |
| DELETE | `/api/api-keys/{keyId}` | See reference | `api-keys:write` | [Details](api_keys_api.md) |
| PUT | `/api/api-keys/{keyId}/scopes` | application/json | `api-keys:write` | [Details](api_keys_api.md) |
| GET | `/api/audit` | application/json | `audit:read` | [Details](audit_api.md) |
| GET | `/api/audit/export` | text/csv | `audit:read` | [Details](audit_api.md) |
| DELETE | `/api/contexts` | application/json | `contexts:write` | [Details](contexts_api.md) |
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
| DELETE | `/api/holds` | application/json | `holds:write` | [Details](legal_holds_api.md) |
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
| GET | `/api/limits` | application/json | Any key | [Details](limits_api.md) |
| DELETE | `/api/lists` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| GET | `/api/lists` | application/json | `lists:read` | [Details](custom_lists_api.md) |
| DELETE | `/api/lists/{name}` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| GET | `/api/lists/{name}` | application/json | `lists:read` | [Details](custom_lists_api.md) |
| POST | `/api/lists/{name}` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| PUT | `/api/lists/{name}` | application/json | `lists:write` | [Details](custom_lists_api.md) |
| GET | `/api/policies` | application/json | `policies:read` | [Details](policies_api.md) |
| POST | `/api/policies` | See reference | `policies:write` | [Details](policies_api.md) |
| POST | `/api/policies/compile` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/policies/templates/{templateName}` | application/json | `policies:read` | [Details](policies_api.md#get-a-policy-template) |
| DELETE | `/api/policies/{policyName}` | See reference | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}` | application/json | `policies:read` | [Details](policies_api.md) |
| PUT | `/api/policies/{policyName}` | See reference | `policies:write` | [Details](policies_api.md) |
| POST | `/api/policies/{policyName}/copy` | application/json | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/details` | application/json | `policies:read` | [Details](policies_api.md) |
| PUT | `/api/policies/{policyName}/details` | application/json | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/diff` | application/json | `policies:read` | [Details](policies_api.md) |
| POST | `/api/policies/{policyName}/rollback` | application/json | `policies:write` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/versions` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/policies/{policyName}/versions/{revision}` | application/json | `policies:read` | [Details](policies_api.md) |
| GET | `/api/redact-lists` | application/json | `lists:read` | [Details](redact_lists_api.md) |
| POST | `/api/redact-lists` | See reference | `lists:write` | [Details](redact_lists_api.md) |
| PUT | `/api/redact-lists` | See reference | `lists:write` | [Details](redact_lists_api.md) |
| GET | `/api/redact-lists/{list}` | application/json | `lists:read` | [Details](redact_lists_api.md#get-one-list) |
| PUT | `/api/redact-lists/{list}` | application/json | `lists:write` | [Details](redact_lists_api.md#replace-one-list) |
| GET | `/api/settings` | application/json | `settings:read` | [Details](settings_api.md) |
| PATCH | `/api/settings` | application/json | `settings:write` | [Details](settings_api.md) |
| POST | `/api/reidentify` | application/json | `reidentify` | [Details](../../redaction/re-identification.md) |
| GET | `/api/sign-in` | application/json | `Public` | [Details](sign_in_api.md#get-the-sign-in-options) |
| POST | `/api/sign-in` | application/json | `Public` | [Details](sign_in_api.md) |
| POST | `/api/sign-in/mfa` | application/json | `Public` | [Details](sign_in_api.md#complete-sign-in-with-a-code) |
| GET | `/api/signing-key` | application/json | `Public` | [Details](public_api.md) |
| GET | `/api/signing-key/{keyId}` | application/json | `Public` | [Details](public_api.md) |
| POST | `/api/signing-key/regenerate` | application/json | `signing:write` | [Details](../../output_signing.md) |
| GET | `/api/users` | application/json | `users:read` | [Details](users_api.md) |
| POST | `/api/users` | application/json | `users:write` | [Details](users_api.md) |
| GET | `/api/users/me` | application/json | `users:read` | [Details](users_api.md) |
| POST | `/api/users/me/mfa` | application/json | `users:write` | [Details](users_api.md#start-enrollment) |
| POST | `/api/users/me/mfa/confirm` | application/json | `users:write` | [Details](users_api.md#confirm-enrollment) |
| POST | `/api/users/me/mfa/remove` | application/json | `users:write` | [Details](users_api.md#remove-your-own-enrollment) |
| PUT | `/api/users/me/password` | application/json | `users:write` | [Details](users_api.md) |
| GET | `/api/users/{username}` | application/json | `users:read` | [Details](users_api.md) |
| GET | `/api/users/{username}/api-keys` | application/json | `api-keys:read` | [Details](api_keys_api.md) |
| POST | `/api/users/{username}/api-keys` | application/json | `api-keys:write` | [Details](api_keys_api.md) |
| POST | `/api/users/{username}/deactivate` | application/json | `users:write` | [Details](users_api.md) |
| DELETE | `/api/users/{username}/mfa` | application/json | `users:write` | [Details](users_api.md#remove-a-users-enrollment) |
| POST | `/api/users/{username}/mfa/unlock` | application/json | `users:write` | [Details](users_api.md#unlock-a-user) |
| PUT | `/api/users/{username}/password` | application/json | `users:write` | [Details](users_api.md) |
| POST | `/api/users/{username}/reactivate` | application/json | `users:write` | [Details](users_api.md) |
| DELETE | `/api/users/{username}/session-keys` | application/json | `api-keys:write` | [Details](api_keys_api.md#revoke-a-users-session-keys) |
| PUT | `/api/users/{username}/role` | application/json | `users:write` | [Details](users_api.md) |
| DELETE | `/api/webhook` | See reference | `webhooks:write` | [Details](webhooks.md) |
| GET | `/api/webhook` | application/json | `webhooks:read` | [Details](webhooks.md) |
| PUT | `/api/webhook` | application/json | `webhooks:write` | [Details](webhooks.md) |
| GET | `/api/webhook/deliveries` | application/json | `webhooks:read` | [Details](webhooks.md#list-the-deliveries) |
| POST | `/api/webhook/test` | application/json | `webhooks:write` | [Details](webhooks.md#send-a-test-event) |

PDF async acceptance returns `application/json` with status 202, regardless of the selected download format. A ZIP result contains `redacted.pdf`. Health is a liveness response, not a dependency-readiness probe. Webhooks are outbound notifications; they are documented separately and are not inbound API routes.
