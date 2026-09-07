# Documents API

The Documents API exposes the lifecycle of asynchronously-submitted PDF redactions. When a PDF is submitted to `POST /api/filter` without `?async=false`, Philter persists the request, returns `202 Accepted` with a `documentId`, and processes it on a background worker. Use the endpoints below to track and retrieve the result.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own documents. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (list, status, download, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

> The `curl` examples assume Philter is enabled for SSL with a self-signed certificate. See the [SSL/TLS settings](../../settings.md) for details.

## How the queue is worked

A background worker claims pending documents and redacts them one after another, draining everything queued before it waits again. The wait between passes is `philter.worker.poll-interval-ms` (default 5,000ms), which is how long an idle worker sleeps rather than a limit on throughput: a backlog is worked through as fast as redaction runs, not one document per interval. The worker has its own thread, so a long document does not delay [webhook](webhooks.md) delivery. A document that repeatedly kills the worker during computation is requeued at most three times, then failed if it is abandoned again.

## Claims and recovery

Each attempt receives a unique claim token and a ten-minute lease. A separate heartbeat renews the lease every minute while the worker computes the redaction. Another instance can reclaim an expired computation; the old attempt can no longer publish completed-result evidence, store output, change the job to failed, or enqueue a completion/failure webhook. These guarantees use MongoDB and apply across instances regardless of the cache backend.

After computation, the worker atomically reserves publication before writing the ledger, completion metrics, or completion audit event. The job remains `PROCESSING`, with `publication_started_at` recorded in `pending_documents`. This reservation does **not** expire: automatic takeover could race evidence writes already issued by the old worker. A successful or failed terminal update must match the attempt token and processing state. An accepted terminal update atomically records `notification_pending=true`. A reconciler retries enqueueing after failures or restarts, including jobs failed by exhausted reclaim. The job ID is the delivery ID, so retrying an interrupted acknowledgement does not create another delivery.

A crash during publication leaves the job `PROCESSING` and requires operator recovery. Such jobs do not have `completed_at`, so their input does not expire under the finished-job TTL. To recover:

1. Inspect the job's `_id`, `claim_token`, `claimed_by`, and `publication_started_at`. Stop the worker process that could resume that attempt and confirm its outstanding database commands have finished.
2. Review the document's ledger chain, completion audit events, stored result, and webhook records. Evidence may be partial or complete even though the job still reports `PROCESSING`.
3. Once the attempt is quiescent, conditionally mark the record `FAILED`, matching its observed `_id`, `claim_token`, `status: PROCESSING`, and publication timestamp. Set `completed_at`, `notification_pending=true`, and a recovery error, and remove `input` and `input_encrypted_key`. The reconciler sets `retention_at` after accepting the notification intent; do not set it during recovery. Preserve existing ledger evidence for review.
4. If redaction is still needed, submit a new job with a new document ID. Do not clear the publication reservation and automatically replay the old document ID: its ledger or notifications may already exist.

Claim fencing governs job results and completed-result publication. Context mapping and disambiguation updates made during computation are not rolled back when an attempt loses ownership.

## Statuses

A submitted document moves through one of the following statuses:

| Status       | Meaning                                                              |
|--------------|----------------------------------------------------------------------|
| `PENDING`    | Submitted, waiting for a worker to claim it.                         |
| `PROCESSING` | Claimed by a worker and being redacted.                              |
| `COMPLETE`   | Redaction finished; the redacted bytes are available for download.   |
| `FAILED`     | Redaction did not complete; `error` field in the status response explains it. |

Terminal records are retained for `PENDING_DOCUMENTS_TTL_SECONDS` (default 7 days) from `retention_at`, set when notification enqueueing is acknowledged. Undispatched intents do not expire and deletion returns HTTP 409 with `Retry-After: 5` while an intent is pending.

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
| `410 Gone`     | The redaction failed. Inspect the status response (or webhook) for the error message.             |
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

## Configuration captured at submission

Async submission freezes the resolved policy, custom-list contents, managed FPE key/tweak, environment key/tweak references, always/never-redact lists, and context settings. The encrypted snapshot is retained independently of the job. `X-Effective-Configuration-SHA256` in the 202 response, `effectiveConfigurationHash` in status, and `effectiveHash` in ledger entries identify it. The ledger hash binds this fingerprint. Raw policy identity remains separately available. Context mappings, vector contents, and execution software remain live; this is a configuration guarantee, not a promise of byte-identical replay. Cached global lists are captured as observed at submission.

Admission limits active jobs and input bytes globally and per account; see [queue settings](../../settings.md#async-queue-admission). Owner limits return 429, and global limits or admission contention return 503, with `Retry-After: 5`.

A failed status response includes `error`, for example `{"documentId":"example","status":"FAILED","error":"Unable to parse PDF."}`. Status also returns the captured `effectiveConfigurationHash` when present. Deletion returns 409 with `Retry-After: 5` while notification reconciliation is pending, in addition to the 200 and 404 outcomes above.
