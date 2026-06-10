# API

Philter's API has the following sections:

* [Redaction API](api/filtering_api.md) - Submit text or PDFs for redaction. In Philter 4.0, PDF requests are asynchronous by default; the redacted bytes are downloaded via the Documents API.
* [Documents API](api/documents_api.md) - List, poll, download, and delete asynchronous PDF redactions.
* [Webhooks](api/webhooks.md) - Receive signed HTTP notifications when an asynchronous redaction completes or fails.
* [Policies API](api/policies_api.md) - Create, modify, and delete [policies](../policies/filter_policies.md). Policies can also be managed in the dashboard.
* [Custom Lists API](api/custom_lists_api.md) - Create, modify, and delete custom lists. Custom lists are referenced by policies to identify terms to redact.
* [Always/Never Redact Lists API](api/redact_lists_api.md) - Get, replace (POST), and append to (PUT) the account's always-redact and never-redact lists, applied across all of your policies.
* [Contexts API](api/contexts_api.md) - Create, update, and delete contexts and inspect their entries. Contexts maintain referential integrity across documents.

## OpenAPI Specification

Philter's API is described by an OpenAPI specification generated from the application's source. It is regenerated on every build, so it always matches the code. You can always find it in any of these places:

* **In this documentation:** [openapi.json](openapi.json) — matches this released version of Philter; no running instance required.
* **In the GitHub repository:** [`docs/docs/api_and_sdks/openapi.json`](https://github.com/philterd/philter/blob/main/docs/docs/api_and_sdks/openapi.json) — the committed copy, reflecting the latest code on `main`.
* **From a running Philter instance:** `https://<your-philter-host>:8080/v3/api-docs` — the live specification served by that instance, reflecting its version and configuration.

Use the specification to generate an API client for your language (for example with [OpenAPI Generator](https://openapi-generator.tech/)). See [Client SDKs](sdks.md) for more.

## Interactive API Reference (Swagger UI)

Every running Philter instance serves an interactive API reference (Swagger UI) generated from the live OpenAPI specification. It lists every endpoint with its parameters, request and response schemas, and lets you try requests directly from the browser.

* **Swagger UI:** `https://<your-philter-host>:8080/swagger-ui/index.html`

You can also reach the Swagger UI from the dashboard: open **My Account → API Keys** and use the **Open the API reference (Swagger UI)** link. To authorize a request from Swagger UI, send your API key as a bearer token in the `Authorization` header (see [API Keys and Authentication](../account/api_keys.md)).

## Securing Philter's API

Philter's API supports one-way and two-way SSL/TLS authentication. See Philter's [Settings](../settings.md) for more information.

## SDKs

The Philter [Java SDK](sdks.md) provides convenient methods for using Philter's API. For other languages, generate a client from the OpenAPI specification above. See [Client SDKs](sdks.md) for more information.
