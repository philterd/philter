# Developers

Philter's REST API is the integration surface for applications, data pipelines, and batch processing. Everything the dashboard does to redaction resources is also available over the API, so policies and redaction workflows can be managed as code.

The [Developer Quick Start](./developer_quick_start.md) walks through creating a policy, redacting text, and redacting a PDF, with `curl` and Python examples.

## API Integration

The API covers:

*   **Redaction**: redact text synchronously and PDFs asynchronously, and inspect what was detected with `POST /api/explain`.
*   **Policy management**: create, retrieve, and delete [policies](../redaction/policies.md), with every save retained as an immutable version that can be compared and rolled back.
*   **Contexts**: manage [contexts](../redaction/contexts.md) and their token-to-replacement entries, including export and import.
*   **Lists**: manage [custom lists](../redaction/custom_lists.md) and [always/never redact lists](../redaction/redact_lists.md).
*   **Evidence**: query and export the [redaction ledger](../redaction/ledgers.md), and set or release [legal holds](../redaction/legal_holds.md).
*   **Re-identification**: reverse a `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE` value with [re-identification](../redaction/re-identification.md), which requires a reason that is recorded in the audit log.

See the [API Reference](../api_and_sdks/api.md) for every endpoint, and [Client SDKs](../api_and_sdks/sdks.md) for the Java SDK and for generating a client in other languages from the OpenAPI specification.

## API Authentication

All API requests authenticate with an API key sent as a bearer token. Manage keys on the [API Keys](../account/api_keys.md) page of the dashboard, or seed one at startup with `PHILTER_BOOTSTRAP_API_KEY`.

```http
Authorization: Bearer <YOUR_API_KEY>
```

`GET /api/status`, `GET /api/health`, and `GET /api/signing-key` are the exceptions: they are served without authentication so load balancers can probe Philter and so verifiers can fetch the public signing key.

## Interactive API Reference

Every running instance serves Swagger UI at `/swagger-ui/index.html` (for example, `https://localhost:8080/swagger-ui/index.html`), where you can explore each endpoint and issue test calls from the browser. The OpenAPI specification itself is at `/v3/api-docs`. Neither requires authentication.

## Developer Guidelines

*   **Restrict access.** Put Philter behind firewall rules that limit API access to trusted clients, and set `API_IP_ALLOWLIST` to constrain which addresses may call the API (see [Settings](../settings.md)).
*   **Handle error responses.** Beyond `401 Unauthorized` for a missing or invalid key, expect `403 Forbidden` (the operation requires an administrator, the feature is disabled, or the caller's address is not in the allowlist), `413 Payload Too Large` and `415 Unsupported Media Type` on redaction requests, `409 Conflict` and `410 Gone` when downloading an asynchronous document that is not finished or that failed, and `423 Locked` when a legal hold blocks a deletion.
*   **Use a separate context for development.** A distinct [context](../redaction/contexts.md) keeps test replacements out of your production data.
*   **Validate against your own data.** Detection is probabilistic, so measure a policy against representative documents before relying on it, and review the results.

## Need Support?

See [Support](../support.md), or contact [support@philterd.ai](mailto:support@philterd.ai).
