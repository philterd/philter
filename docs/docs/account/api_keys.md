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

* **Create a key.** Philter generates the key and shows it once. Use the **Copy** button to copy it to your clipboard, then store it securely; it cannot be retrieved again afterward.
* **Delete a key.** Deleting a key immediately revokes it: subsequent requests using that key are rejected with `401 Unauthorized`. Deletion is permanent and a key cannot be reactivated. The key record itself is retained (marked deleted) so that audit entries which reference the key id still resolve to it; deleted keys are not shown in the list. Generate a new key if you need access again.

A user may have more than one API key (for example, one per integration), which makes it possible to rotate or revoke a single key without disrupting others.

API keys can also be managed programmatically; an API key's creation and deletion are recorded in the [audit log](../auditing.md).

A deactivated user's API keys are also rejected for as long as the account is deactivated, even though the keys themselves are not deleted; reactivating the user restores them. See [User Management](../dashboard.md#user-management).

## Bootstrapping an API key for automation

Creating a key in the dashboard is the normal path, but turnkey deployments (cloud marketplace images, Terraform, Docker Compose) often need a key without an interactive step. Set the `PHILTER_BOOTSTRAP_API_KEY` environment variable to a value of the form `sk_` followed by 32 alphanumeric characters, and Philter assigns that key to the `admin` user at startup.

The key is only seeded when the `admin` user has no API keys at all, counting both active and archived (deleted) keys. So it is created once on a fresh install, and once you have created a key of your own (or revoked the bootstrap key), it is never seeded again on a later restart.

While the bootstrap key is in use, Philter makes it visible so it does not become a forgotten, long-lived credential: the admin sees a warning on login, and the **API Keys** page shows a banner identifying the bootstrap key (including its value, read from the environment of the running instance) with a prompt to create your own key and delete it.

Authentication stays fully enabled; the bootstrap key is your own secret, provisioned the same way you supply other secrets. Treat it like any credential and rotate or revoke it in the dashboard once it is no longer needed. See [Settings](../settings.md#api-access).

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
