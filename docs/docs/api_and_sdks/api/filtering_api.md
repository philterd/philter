# Redaction API

Philter’s Redaction API provides access to Philter’s ability to redact sensitive information from text and to retrieve the health status of Philter. The endpoint path is `/api/filter` (Philter is named for the filter engine that powers redaction).

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.

Each filter request can optionally have a `context`. The context is optional: when it is omitted (or sent as an empty value) the request uses no context features — token replacements are not persisted or shared across requests, and any entity-type disambiguation is limited to the single document being filtered. Contexts provide a means for logically grouping your documents during filtering. For example, documents pertaining to one health care provider may be submitted under the context `hospital1`, and documents pertaining to another health care provider may be submitted under the context `hospital2`.

The context for each filter request impacts how sensitive information is replaced when found in the text. [Referential integrity](../../other_features/referential_integrity.md) can be enabled at either the context or document level. When enabled at the context level, all instances of a given piece of sensitive information will be replaced consistently by the same value. This allows for maintaining meaning across all documents in the context.

Each filter request submitted to Philter is automatically assigned a document identifier. The document identifier is an alphanumeric value unique to that request. No two documents should be assigned the same document identifier. The document identifier is returned in the `x-document-id` header with each `filter` or `explain` API response.

## Filter

The `filter` endpoint receives plain text or a PDF document and returns the redacted text or redacted PDF document.

The types of sensitive information found and how each type is redacted is determined by the chosen policy.

| Method | Endpoint      | Description            |
|--------|---------------|------------------------| 
| `POST` | `/api/filter` | Filter the given text. |

### Query Parameters

* `p` - The name of the policy to use for filtering. Defaults to `default` if not provided.
* `c` - The filtering context. Optional; when omitted or empty, no context is used (see above).
* `async` - **PDF only.** Whether to process the request asynchronously. Defaults to `true`. The text endpoint is always synchronous and ignores this parameter.
* `filename` - Optional. A filename to record with this request in the [redaction ledger](../../redaction/ledgers.md). When omitted, the ledger records `none-provided`.

### Headers

* `Authorization` - The value should be set to `Bearer <token>` where `<token>` is your API key.
* `Content-Type` - The value should be set to `text/plain` or `application/pdf`.

### Response Headers

Every `filter` response reports which policy version governed the request, so the applied policy is recorded without a second call:

* `X-Philter-Policy-Name` - The name of the policy that was applied.
* `X-Philter-Policy-Version` - The revision (version) of that policy that was applied. For an asynchronous PDF request the version is pinned when the request is accepted, and the `202 Accepted` response carries these headers; the deferred redaction is then governed by that pinned version.

Every successful plain-text `filter` response (200 OK) also includes:

* `X-Document-Id` - A UUID that uniquely identifies this specific request/response. This value is also bound into the `X-Philter-Signature` JWT payload as `documentId` when output signing is enabled.

When [output signing](../../output_signing.md) is enabled in Admin Settings, successful plain-text `filter` responses additionally include:

* `X-Philter-Signature` - A compact ES256 JWT that cryptographically attests the response body. The JWT payload contains:
  * `bodyHash` — SHA-256 (lowercase hex) of the response body.
  * `policyName` — name of the applied policy.
  * `policyVersion` — revision of the applied policy.
  * `documentId` — the value from the `X-Document-Id` response header.
  * `iat` — Unix epoch (seconds) when the JWT was issued.

  Verify the signature using the public key from `GET /api/signing-key`. See [Output Signing](../../output_signing.md) for full documentation and code examples. PDF responses are not signed; if signing is enabled and the signing operation fails, the request returns HTTP 500.

### Plain text

Plain text redaction is always synchronous. The response body is the redacted text.

```
curl -k -X POST "https://localhost:8080/api/filter" -d @file.txt -H "Content-Type: text/plain" -H "Authorization: Bearer <token>"
```

### PDF documents

PDF redaction is **asynchronous by default** in Philter 4.0. The server enqueues the request and returns `202 Accepted` with a JSON body containing the assigned `documentId` and a `Location: /api/documents/{documentId}` header. The redacted bytes can then be downloaded from the [Documents API](documents_api.md) once the job's status is `COMPLETE`.

```
curl -k -X POST "https://localhost:8080/api/filter" \
  -d @file.pdf \
  -H "Content-Type: application/pdf" \
  -H "Authorization: Bearer <token>" \
  -i
```

Example response (async, default):

```
HTTP/1.1 202 Accepted
Location: /api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f
Content-Type: application/json

{"documentId":"c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f"}
```

To receive the redacted PDF inline (3.x behavior), append `?async=false`:

```
curl -k -X POST "https://localhost:8080/api/filter?async=false" \
  -d @file.pdf \
  -H "Content-Type: application/pdf" \
  -H "Authorization: Bearer <token>" \
  -o redacted.pdf
```

> **Migration from 3.x.** Clients that previously consumed the redacted bytes directly from `POST /api/filter` should either (a) add `?async=false` to keep the synchronous behavior, or (b) switch to the async flow (poll `GET /api/documents/{id}/status`, then download from `GET /api/documents/{id}`). Async deliveries can additionally trigger a signed [webhook](webhooks.md) when they complete.

## Explain

The `explain` endpoint behaves much like the `filter` endpoint in that receives plain text and returns the redacted plain text. However, the `explain` endpoint provides a detailed explanation describing how the text was redacted. Also, the `explain` endpoint does not support PDF documents.

The types of sensitive information found and how each type is redacted is determined by the chosen policy.

| Method | Endpoint       | Description                                               |
|--------|----------------|-----------------------------------------------------------| 
| `POST` | `/api/explain` | Filter the given text and provide a detailed explanation. |

### Query Parameters

* `p` - The name of the policy to use for filtering. Defaults to `default` if not provided.
* `c` - The filtering context. Optional; when omitted or empty, no context is used (see above).
* `filename` - Optional. A filename to record with this request in the [redaction ledger](../../redaction/ledgers.md). When omitted, the ledger records `none-provided`.

### Headers

* `Authorization` - The value should be set to `Bearer <token>` where `<token>` is your API key.
* `Content-Type` - The value should be set to `text/plain`.

Example explain request:

```
curl -k -X POST "https://localhost:8080/api/explain" -d @file.txt -H "Content-Type: text/plain" -H "Authorization: Bearer <token>"
```

Example explain response:

The response also reports which policy version was applied, in the `policyName` and `policyVersion` fields.

```
{
  "filteredText": "{{{REDACTED-entity}}} was a patient and his ssn was {{{REDACTED-ssn}}}.",
  "context": "",
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "policyName": "default",
  "policyVersion": 3,
  "explanation": {
    "appliedSpans": [
      {
        "id": "c78fb69c-84d6-4189-b376-63791793cbd2",
        "characterStart": 0,
        "characterEnd": 17,
        "filterType": "NER_ENTITY",
        "context": "C1",
        "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
        "confidence": 0.9189682900905609,
        "text": "George Washington",
        "replacement": "{{{REDACTED-entity}}}",
        "ignored": false
      },
      {
        "id": "f4556f62-2f80-4edc-96f0-aa1d44802157",
        "characterStart": 48,
        "characterEnd": 59,
        "filterType": "SSN",
        "context": "C1",
        "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
        "confidence": 1,
        "text": "123-45-6789",
        "replacement": "{{{REDACTED-ssn}}}",
        "ignored": false
      }
    ],
    "ignoredSpans": []
  }
}
```

## Status

The `status` endpoint is useful in determining the current state of Philter. The `status` endpoint can be used by monitoring software to assess Philter's availability or by your cloud provider for purposes of determining Philter's health when deployed behind a load balancer.

| Method | Endpoint      | Description                 |
|--------|---------------|-----------------------------| 
| `GET`  | `/api/status` | Gets the status of Philter. |

Example request:

```
curl -k -X GET "https://localhost:8080/api/status"
```
