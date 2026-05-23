# Documents API

The Documents API exposes the lifecycle of asynchronously-submitted PDF redactions. When a PDF is submitted to `POST /api/filter` without `?async=false`, Philter persists the request, returns `202 Accepted` with a `documentId`, and processes it on a background worker. Use the endpoints below to track and retrieve the result.

> The `curl` examples assume Philter is enabled for SSL with a self-signed certificate. See the [SSL/TLS settings](../../settings.md) for details.

## Statuses

A submitted document moves through one of the following statuses:

| Status       | Meaning                                                              |
|--------------|----------------------------------------------------------------------|
| `PENDING`    | Submitted, waiting for a worker to claim it.                         |
| `PROCESSING` | Claimed by a worker and being redacted.                              |
| `COMPLETE`   | Redaction finished; the redacted bytes are available for download.   |
| `FAILED`     | Redaction did not complete; `error` field on the record explains it. |

Completed records are retained for `PENDING_DOCUMENTS_TTL_SECONDS` (default 7 days) before MongoDB's TTL index removes them.

## List Documents

| Method | Endpoint         | Description                                              |
|--------|------------------|----------------------------------------------------------|
| `GET`  | `/api/documents` | Paginated list of submissions for the calling API key.   |

### Query Parameters

* `offset` (optional, default `0`)
* `limit` (optional, default `25`, max `100`)

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/documents?offset=0&limit=25"
```

```json
{
  "pendingRedactedDocuments": [
    {
      "fileName": "patient-record.pdf",
      "status": "COMPLETE",
      "timestamp": "2026-05-22T20:00:00.000+00:00",
      "documentId": "c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f"
    }
  ]
}
```

## Get Status

| Method | Endpoint                                     | Description                          |
|--------|----------------------------------------------|--------------------------------------|
| `GET`  | `/api/documents/{documentId}/status`         | Current status of a single document. |

```bash
curl -k -H "Authorization: Bearer <token>" \
  https://localhost:8080/api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f/status
```

```json
{
  "documentId": "c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f",
  "status": "PROCESSING"
}
```

Returns `404 Not Found` if no document with that id exists for the calling user.

## Download

| Method | Endpoint                          | Description                          |
|--------|-----------------------------------|--------------------------------------|
| `GET`  | `/api/documents/{documentId}`     | Download the redacted bytes.         |

| Response       | Meaning                                                                                   |
|----------------|-------------------------------------------------------------------------------------------|
| `200 OK`       | Redacted bytes. `Content-Type` matches the requested output (`application/pdf` or `application/zip`). |
| `409 Conflict` | The document exists but the redaction has not yet completed. Poll the status endpoint and retry. |
| `410 Gone`     | The redaction failed. Inspect the record (or webhook) for the error message.             |
| `404 Not Found`| No document with that id exists for the calling user (or it has been TTL-evicted).        |

```bash
curl -k -H "Authorization: Bearer <token>" \
  -o redacted.pdf \
  https://localhost:8080/api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f
```

## Delete

| Method   | Endpoint                          | Description                                       |
|----------|-----------------------------------|---------------------------------------------------|
| `DELETE` | `/api/documents/{documentId}`     | Remove the record and any stored redacted bytes.  |

Returns `200 OK` on success, `404 Not Found` if no document with that id exists for the calling user.

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" \
  https://localhost:8080/api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f
```

## Notifications

If a webhook URL and secret are configured for your user, Philter sends a signed POST when each async redaction reaches `COMPLETE` or `FAILED`. See the [Webhooks](webhooks.md) page for the headers, payload shape, and replay-protected signing scheme.
