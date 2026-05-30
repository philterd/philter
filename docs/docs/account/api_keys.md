# API Keys and Authentication

Every request to Philter's REST API must be authenticated with an API key. API keys are managed per user account.

## API key format

Philter API keys start with the prefix `sk_` followed by 32 alphanumeric characters, for example:

```
sk_abcdefghijklmnopqrstuvwxyz012345
```

Keys are stored only as a SHA-256 hash; Philter cannot recover the original key after it is created. A short prefix of each key is retained so you can recognize it in the dashboard.

## Authenticating a request

Send the API key in the HTTP `Authorization` header using the `Bearer` scheme on every API request:

```http
Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345
```

For example, redacting text with `curl`:

```
curl -k "https://localhost:8080/api/filter" \
  --data "George Washington lives in 90210." \
  -H "Content-type: text/plain" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345"
```

A request with a missing, malformed, or unknown key is rejected with `401 Unauthorized`. These failures are recorded in the [audit log](../auditing.md).

## Managing API keys

API keys are created and removed in the dashboard, under **API Keys**:

* **Create a key.** Philter generates the key and shows it once. Copy it and store it securely; it cannot be retrieved again afterward.
* **Delete a key.** Deleting a key immediately revokes it. Subsequent requests using that key are rejected.

A user may have more than one API key (for example, one per integration), which makes it possible to rotate or revoke a single key without disrupting others.

API keys can also be managed programmatically; an API key's creation and deletion are recorded in the [audit log](../auditing.md).

## Restricting access by IP address

You can optionally restrict which client IP addresses may call the API with the `API_IP_ALLOWLIST` environment variable (see [Settings](../settings.md#api-access)). When set, an otherwise-authenticated request from an address that is not on the allowlist is rejected with `403 Forbidden` and the denial is recorded in the [audit log](../auditing.md).

## Unauthenticated endpoints

A small number of endpoints do not require an API key:

* `/api/status` and `/api/health` (the status/health endpoints).
* `/v3/api-docs` and `/swagger-ui/` (the OpenAPI specification and Swagger UI).

All other `/api/` endpoints require a valid API key.

## Transport security

Philter's API is served over HTTPS. Cloud marketplace deployments use a self-signed certificate by default, which is why the examples above pass `-k` to `curl`. Use a certificate trusted by your clients in production.

## See also

* [Developers](../developers/developers.md)
* [Auditing](../auditing.md)
* [Settings](../settings.md)
