# Webhooks

When Philter completes or fails an asynchronous PDF redaction, it can notify your application with a signed HTTP POST. Configure a single webhook URL and shared secret per user, with the `/api/webhook` endpoints below.

> Webhooks only fire from the [asynchronous filter path](filtering_api.md#pdf-documents). Synchronous redactions return the result on the request itself and never produce a webhook.

## Configuration

| Field    | Purpose                                                                                   |
|----------|-------------------------------------------------------------------------------------------|
| `url`    | Absolute `https://` (or `http://`) URL to which Philter will POST the event. Its host must be a permitted [destination](#where-a-webhook-may-point). |
| `secret` | Shared secret used to HMAC-sign each request body. Minimum 16 characters; 48 recommended. |

A URL or secret that fails validation is refused with the reason, and nothing is saved. To generate a 48-character secret, run `openssl rand -hex 24`.

### Read the webhook

```
GET /api/webhook
```

Requires the `webhooks:read` [scope](../../account/api_keys.md#scopes). Returns the URL and whether a secret is set. The secret is never returned.

```json
{
  "url": "https://hooks.example.com/philter",
  "secretSet": true
}
```

With no webhook set, `url` is `null` and `secretSet` is `false`.

### Set the webhook

```
PUT /api/webhook
```

Requires `webhooks:write`. Replaces the URL and secret and returns the configuration as above.

```json
{
  "url": "https://hooks.example.com/philter",
  "secret": "a-shared-secret-of-at-least-16-characters"
}
```

A missing URL or secret, a URL that is not `http` or `https`, a destination that is not permitted, or a secret shorter than 16 characters is refused with `400 Bad Request` and a message saying which.

```
curl -k -X PUT "https://localhost:8080/api/webhook" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"url":"https://hooks.example.com/philter","secret":"a-shared-secret-of-at-least-16-characters"}'
```

### Remove the webhook

```
DELETE /api/webhook
```

Requires `webhooks:write`. Removes the URL and secret, so results are no longer delivered, and returns `204 No Content`.

### Another user's webhook

An administrator can read, set, or remove another user's webhook by adding `owner=<username>` to any of these requests. This requires `ADMIN_CROSS_USER_ACCESS_ENABLED=true`, as for other cross-user access. An owner that does not exist or cannot be reached returns `404 Not Found`. A deactivated user may be named as `owner`.

### Auditing

Setting and removing a webhook are recorded as `webhook_configured` and `webhook_removed` [audit events](../../auditing.md), naming the calling user and API key. The URL and secret are not recorded. A refused attempt is not recorded.

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
| `X-Philter-Event`         | The event type. See the table above.                                                        |
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
|     8   | No retry                  |

Each attempt is bounded: Philter waits `WEBHOOK_CONNECT_TIMEOUT_SECONDS` (default 5) to connect and `WEBHOOK_RESPONSE_TIMEOUT_SECONDS` (default 10) for your response. Exceeding either counts as a failed attempt and is retried on the schedule above, so **acknowledge the delivery promptly and do your processing asynchronously**: holding the connection open while you work will time out. See [Settings](../../settings.md#asynchronous-documents-and-webhooks).

After the 8th failure, the delivery is marked `FAILED` and no further attempts are made. Both delivered and failed records receive `completed_at` and expire after `WEBHOOK_DELIVERIES_TTL_SECONDS` (default 30 days). This includes exhausted abandoned claims; pending retries do not expire.

## Where a webhook may point

Philter refuses to deliver to private, loopback and link-local addresses, so a webhook cannot be aimed at the network Philter itself sits on. An administrator can widen or narrow that with the [Settings API](settings_api.md) (`webhookAllowlist`), with a comma-separated list of hostnames and IP addresses or CIDR ranges:

```
hooks.example.com, 203.0.113.0/24, 10.4.0.0/16
```

Leaving it empty allows any public address. Listing an internal range permits that range and no other, which is how to deliver to a collector inside your own network.

The rule is applied when a URL is saved, again before each delivery, and once more against the address the hostname actually resolves to at the moment of connection. Redirects are not followed, so a permitted endpoint cannot forward a delivery somewhere else. A delivery refused by the policy is recorded as a failed attempt.

## Operational notes

* Any Philter instance can deliver a queued webhook. Workers claim attempts atomically through MongoDB; an active claim excludes other workers. Each attempt has a fresh claim token and a lease controlled by `WEBHOOK_CLAIM_LEASE_SECONDS` (default 300 seconds).
* If a worker stops or exceeds its lease, another worker can recover the delivery. Results from an expired or superseded claim cannot overwrite a newer attempt. Abandoned claims count toward the eight-attempt limit; an expired final attempt is marked `FAILED`.
* Delivery is at least once: a receiver may accept a request just before the worker loses its claim or its connection. Deduplicate using `X-Philter-Delivery-Id`. Set the lease longer than the configured HTTP timeouts plus processing overhead; lease recovery cannot cancel a request already sent to the receiver.
* The worker poll interval is configurable via `philter.webhook.poll-interval-ms` (default 5,000ms). Each poll delivers everything that is due rather than one delivery, so the interval is how long an idle worker waits, not a rate limit.
* The redacted document bytes are *not* included in the payload. Fetch them via [`GET /api/documents/{documentId}`](documents_api.md#download) once you see a `COMPLETE` event.

## Durable notification intent

Job completion and failure atomically persist notification intent. Reconciliation retries after enqueue errors and restarts, rotating failed intents so they do not block others. The job ID is the stable delivery ID; enqueue is idempotent, while HTTP delivery remains at least once. The reconciler reads the current account URL and secret at dispatch. With no configured URL it acknowledges the intent without a delivery; a URL without a secret retains the intent for retry. Once enqueued, a delivery retains its encrypted secret and URL. Undispatched jobs neither expire nor allow deletion until reconciliation acknowledges the intent.
