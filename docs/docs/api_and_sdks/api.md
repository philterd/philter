# API

Philter has no built-in user interface: everything, including administration, is done through its API. A user interface signs people in with their own username and password through the [Sign-in API](api/sign_in_api.md) and uses a session key for each person, as [Philter UI](https://github.com/philterd/philter-ui), in development, is being built to; see [Sign-in Security](../sign_in_security.md).

Philter's API has the following sections:

* [Redaction API](api/filtering_api.md) - Submit text or PDFs for redaction. In Philter 4.0, PDF requests are asynchronous by default; the redacted bytes are downloaded via the Documents API.
* [Documents API](api/documents_api.md) - List, poll, download, and delete asynchronous PDF redactions.
* [Webhooks](api/webhooks.md) - Receive signed HTTP notifications when an asynchronous redaction completes or fails.
* [Policies API](api/policies_api.md) - Create, modify, and delete [policies](../policies/filter_policies.md).
* [Custom Lists API](api/custom_lists_api.md) - Create, modify, and delete custom lists. Custom lists are referenced by policies to identify terms to redact.
* [Always/Never Redact Lists API](api/redact_lists_api.md) - Get, replace (POST), and append to (PUT) the account's always-redact and never-redact lists, applied across all of your policies.
* [Contexts API](api/contexts_api.md) - Create, update, and delete contexts and inspect their entries. Contexts maintain referential integrity across documents.
* [Redaction Ledger API](api/ledger_api.md) - List, read, verify, export, and delete [redaction ledger](../redaction/ledgers.md) chains.
* [Legal Holds API](api/legal_holds_api.md) - Set, list, and release [legal holds](../redaction/legal_holds.md) that block deletion of evidence.
* [Users API](api/users_api.md) - Create, list, promote, deactivate, and reactivate users, and manage their passwords.
* [Sign-in API](api/sign_in_api.md) - Exchange a username and password for a session key. Disabled by default.
* [API Keys API](api/api_keys_api.md) - Create, re-scope, and revoke API keys.
* [Settings API](api/settings_api.md) - Read and change the deployment's administrator settings.
* [Audit API](api/audit_api.md) - List and export the [audit log](../auditing.md).

## OpenAPI Specification

Philter's API is described by an OpenAPI specification generated from the application's source. The OpenAPI export integration test regenerates it and checks it against the registered routes and the committed copy. Run that test when changing an endpoint; a successful ordinary compilation alone does not refresh the published artifact. You can always find it in any of these places:

* **In this documentation:** [openapi.json](openapi.json). This matches the released version of Philter and needs no running instance.
* **In the GitHub repository:** [`docs/docs/api_and_sdks/openapi.json`](https://github.com/philterd/philter/blob/main/docs/docs/api_and_sdks/openapi.json). This is the committed copy, reflecting the latest code on `main`.
* **From a running Philter instance:** `https://<your-philter-host>:8080/v3/api-docs`. This is the live specification served by that instance, reflecting its version and configuration.

Use the specification to generate an API client for your language (for example with [OpenAPI Generator](https://openapi-generator.tech/)). See [Client SDKs](sdks.md) for more.

## Interactive API Reference (Swagger UI)

Every running Philter instance serves an interactive API reference (Swagger UI) generated from the live OpenAPI specification. It lists every endpoint with its parameters, request and response schemas, and lets you try requests directly from the browser.

* **Swagger UI:** `https://<your-philter-host>:8080/swagger-ui/index.html`

To authorize a request from Swagger UI, send your API key as a bearer token in the `Authorization` header (see [API Keys and Authentication](../account/api_keys.md)).

## Securing Philter's API

Philter serves its API over HTTPS, using a self-signed certificate generated on first start unless you supply your own. See [TLS](../settings.md#tls) for how to replace the certificate or terminate TLS in front of Philter.

## SDKs

The Philter [Java SDK](sdks.md) provides convenient methods for using Philter's API. For other languages, generate a client from the OpenAPI specification above. See [Client SDKs](sdks.md) for more information.

## Complete endpoint inventory

The [endpoint inventory](api/endpoint_inventory.md) lists every HTTP operation, its required scope, and its detailed reference. Additional API sections include the [Redaction Ledger API](api/ledger_api.md), [Legal Holds API](api/legal_holds_api.md), [policy history and rollback](api/policies_api.md#policy-version-history), [re-identification](../redaction/re-identification.md), and [public health and signing keys](api/public_api.md).
