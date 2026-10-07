# Developers

Philter's REST API is the integration surface for applications, data pipelines, and batch processing, and the only way to administer Philter, which has no built-in user interface. Policies, redaction workflows, users, and keys can all be managed as code.

The [Developer Quick Start](./developer_quick_start.md) walks through creating a policy, redacting text, and redacting a PDF, with `curl` and Python examples.

## API Integration

The API covers:

*   **Redaction**: redact text synchronously and PDFs asynchronously, and inspect what was detected with `POST /api/explain`.
*   **Policy management**: create, retrieve, and delete [policies](../redaction/policies.md), with every save retained as an immutable version that can be compared and rolled back.
*   **Contexts**: manage [contexts](../redaction/contexts.md) and their token-to-replacement entries, including export and import.
*   **Lists**: manage [custom lists](../redaction/custom_lists.md) and [always/never redact lists](../redaction/redact_lists.md).
*   **Evidence**: query and export the [redaction ledger](../redaction/ledgers.md), and set or release [legal holds](../redaction/legal_holds.md).
*   **Administration**: manage [users](../api_and_sdks/api/users_api.md), [API keys](../api_and_sdks/api/api_keys_api.md), [settings](../api_and_sdks/api/settings_api.md), and the [audit log](../api_and_sdks/api/audit_api.md).
*   **Re-identification**: reverse a `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE` value with [re-identification](../redaction/re-identification.md), which requires a reason that is recorded in the audit log.

See the [API Reference](../api_and_sdks/api.md) for every endpoint, and [Client SDKs](../api_and_sdks/sdks.md) for the Java SDK and for generating a client in other languages from the OpenAPI specification.

## API Authentication

All API requests authenticate with an API key sent as a bearer token. The first key is seeded at startup from `PHILTER_BOOTSTRAP_API_KEY`; manage further keys with the [API Keys API](../api_and_sdks/api/api_keys_api.md). See [API Keys and Authentication](../account/api_keys.md).

```http
Authorization: Bearer <YOUR_API_KEY>
```

`GET /api/health`, `GET /api/signing-key`, and `GET /api/signing-key/{keyId}` are the exceptions: they are served without authentication so load balancers can probe Philter and so verifiers can fetch the public signing keys. When `PASSWORD_SIGN_IN_ENABLED=true`, `POST /api/sign-in` and `POST /api/sign-in/mfa` are also served without an API key (they take a username and password); otherwise they return `404`.

## Interactive API Reference

Every running instance serves Swagger UI at `/swagger-ui/index.html` (for example, `https://localhost:8080/swagger-ui/index.html`), where you can explore each endpoint and issue test calls from the browser. The OpenAPI specification itself is at `/v3/api-docs`. Neither requires authentication.

## Developer Guidelines

*   **Restrict access.** Put Philter behind firewall rules, security groups, or an ingress that limits API access to trusted clients. Philter itself does not filter by client address; that belongs at the network layer.
*   **Handle error responses.** Beyond `401 Unauthorized` for a missing or invalid key, expect `403 Forbidden` (the operation requires an administrator, the feature is disabled, or the API key lacks the required scope), `404 Not Found` when you name another user with `owner=` and may not reach them (the API answers the same way whether or not that user exists, so the status cannot be used to discover accounts), `413 Payload Too Large` and `415 Unsupported Media Type` on redaction requests, `409 Conflict` and `410 Gone` when downloading an asynchronous document that is not finished or that failed, and `423 Locked` when a legal hold blocks a deletion.
*   **Use a separate context for development.** A distinct [context](../redaction/contexts.md) keeps test replacements out of your production data.
*   **Validate against your own data.** Detection is probabilistic, so measure a policy against representative documents before relying on it, and review the results.

## Need Support?

See [Support](../support.md), or contact [support@philterd.ai](mailto:support@philterd.ai).
