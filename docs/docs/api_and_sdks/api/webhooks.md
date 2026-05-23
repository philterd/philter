# Webhooks

When Philter completes or fails an asynchronous PDF redaction, it can notify your application with a signed HTTP POST. Configure a single webhook URL and shared secret per user, either through the **Settings → Webhook** section of the dashboard or by updating the user document directly.

> Webhooks only fire from the [asynchronous filter path](filtering_api.md#pdf-documents). Synchronous redactions return the result on the request itself and never produce a webhook.

## Configuration

| Field            | Purpose                                                                                  |
|------------------|------------------------------------------------------------------------------------------|
| `webhookUrl`     | Absolute `https://` (or `http://`) URL to which Philter will POST the event.             |
| `webhookSecret`  | Shared secret used to HMAC-sign each request body. Minimum 16 characters; 48 recommended. |

The dashboard's **Settings → Webhook** section provides a "Generate" button that creates a 48-character secret using `SecureRandom`. The secret can be revealed with the password field's eye icon. Saving with no URL or secret has no effect; use **Remove Webhook** to disable delivery.

## Events

| Event                              | When Philter sends it                                      |
|------------------------------------|------------------------------------------------------------|
| `DOCUMENT_REDACTION_COMPLETE`      | The async worker successfully redacted a document.         |
| `DOCUMENT_REDACTION_FAILED`        | The async worker could not complete the redaction.         |

## Request shape

Every delivery is a `POST` of `application/json`. Example:

```
POST /your-philter-webhook HTTP/1.1
Host: example.com
Content-Type: application/json
X-Philter-Event: DOCUMENT_REDACTION_COMPLETE
X-Philter-Delivery-Id: 6a1106e9f5b4e90cb1d35a01
X-Philter-Timestamp: 1746916800
X-Philter-Signature: sha256=2c1d9...e7f0

{
  "event": "DOCUMENT_REDACTION_COMPLETE",
  "documentId": "c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f",
  "fileName": "patient-record.pdf",
  "status": "COMPLETE",
  "timestamp": "2026-05-22T21:00:00Z"
}
```

For `DOCUMENT_REDACTION_FAILED` deliveries, the payload additionally carries an `error` field with the failure message.

### Headers

| Header                    | Description                                                                                  |
|---------------------------|----------------------------------------------------------------------------------------------|
| `X-Philter-Event`         | The event type — see the table above.                                                        |
| `X-Philter-Delivery-Id`   | The id of the delivery attempt. Stable across retries of the same delivery.                  |
| `X-Philter-Timestamp`     | Unix timestamp (seconds) of signing. Refreshed on every retry.                               |
| `X-Philter-Signature`     | `sha256=<hex>` HMAC-SHA256 of `<timestamp>.<body>` using the shared secret.                  |

## Verifying a request

The signed string is **the timestamp, a literal `.`, and the raw JSON body**, hashed with HMAC-SHA256 using the shared secret. Receivers must:

1. Recompute the HMAC over `"<timestamp>.<body>"` and compare it to `X-Philter-Signature` using a constant-time comparison.
2. Reject any request whose `X-Philter-Timestamp` is more than 5 minutes off from the current wall clock. This binds the timestamp into the signature and prevents replay of captured deliveries.

### Python

```python
import hmac, hashlib, time

def verify(headers, body, secret, tolerance_seconds=300):
    ts = int(headers["X-Philter-Timestamp"])
    sig = headers["X-Philter-Signature"].removeprefix("sha256=")
    expected = hmac.new(
        secret.encode(),
        f"{ts}.{body}".encode(),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(expected, sig):
        raise ValueError("bad signature")
    if abs(time.time() - ts) > tolerance_seconds:
        raise ValueError("stale timestamp")
```

### Node.js

```javascript
import crypto from "crypto";

function verify(headers, body, secret, toleranceSeconds = 300) {
  const ts = parseInt(headers["x-philter-timestamp"], 10);
  const sig = headers["x-philter-signature"].replace(/^sha256=/, "");
  const expected = crypto
    .createHmac("sha256", secret)
    .update(`${ts}.${body}`)
    .digest("hex");
  if (!crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(sig))) {
    throw new Error("bad signature");
  }
  if (Math.abs(Date.now() / 1000 - ts) > toleranceSeconds) {
    throw new Error("stale timestamp");
  }
}
```

## Retry behavior

A delivery is considered successful if your endpoint responds with a `2xx` status within the connection's response timeout. Any other outcome (non-2xx, timeout, connection error) schedules a retry.

Retries use this backoff schedule, regenerating the timestamp and signature on every attempt:

| Attempt | Delay before next attempt |
|--------:|--------------------------:|
|     1   | 30s                       |
|     2   | 1m                        |
|     3   | 5m                        |
|     4   | 15m                       |
|     5   | 30m                       |
|     6   | 1h                        |
|     7   | 2h                        |
|     8   | 4h                        |

After the 8th failure, the delivery is marked `FAILED` and no further attempts are made. Delivered records expire from the `webhook_deliveries` collection after `WEBHOOK_DELIVERIES_TTL_SECONDS` (default 30 days).

## Operational notes

* Webhooks fire from the same Philter instance that processes the async job. Multiple Philter instances coordinate via MongoDB; only one instance will deliver a given attempt.
* The worker poll interval is configurable via `philter.webhook.poll-interval-ms` (default 5,000ms).
* The redacted document bytes are *not* included in the payload — fetch them via [`GET /api/documents/{documentId}`](documents_api.md#download) once you see a `COMPLETE` event.
