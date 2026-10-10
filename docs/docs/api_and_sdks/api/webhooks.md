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

Requires `webhooks:write`. Replaces the URL and secret and returns the configuration as above. Leave `secret` out to keep the secret already set, so the URL can change without rotating the secret; a secret is required when none is set yet.

```json
{
  "url": "https://hooks.example.com/philter",
  "secret": "a-shared-secret-of-at-least-16-characters"
}
```

A missing URL, a missing secret when none is set yet, a URL that is not `http` or `https`, a destination that is not permitted, or a secret shorter than 16 characters is refused with `400 Bad Request` and a message saying which.

Deliveries already queued keep the URL and secret they were queued with; a change applies to events queued afterwards.

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

### Send a test event

```
POST /api/webhook/test
```

Requires `webhooks:write`. Sends one `WEBHOOK_TEST` event to the webhook now, signed with the secret and checked against the [destination rules](#where-a-webhook-may-point) like a real delivery, and returns what happened. Use it to check a webhook after setting it, without waiting for a redaction to finish.

```json
{
  "delivered": true,
  "statusCode": 204,
  "error": null,
  "durationMillis": 118,
  "deliveryId": "6a1106e9f5b4e90cb1d35a7c"
}
```

* `delivered` - Whether your endpoint answered with a `2xx` status.
* `statusCode` - The status your endpoint answered with, or `null` when there was no answer: the connection failed or timed out, or the destination is not permitted.
* `error` - Why the test was not delivered, or `null`.
* `durationMillis` - How long the attempt took.
* `deliveryId` - The event's `X-Philter-Delivery-Id`, to find it in your endpoint's logs.

The test is a single attempt. It is not queued, not retried, and does not appear in the [delivery list](#list-the-deliveries). Philter waits for your endpoint up to `WEBHOOK_CONNECT_TIMEOUT_SECONDS` and `WEBHOOK_RESPONSE_TIMEOUT_SECONDS`, as for a real delivery. The response is `200 OK` whether or not your endpoint accepted the event; with no webhook set it is `409 Conflict`. The event's body is marked as a test, so your endpoint can acknowledge it and do nothing else:

```json
{
  "event": "WEBHOOK_TEST",
  "test": true,
  "timestamp": "2026-10-10T21:00:00Z"
}
```

### List the deliveries

```
GET /api/webhook/deliveries?offset=0&limit=25
```

Requires `webhooks:read`. Lists your deliveries, newest first, and the total, so you can see why a webhook is failing. `limit` defaults to 25 and is capped at 100. `order=asc` lists the oldest first; see [Listings](../api.md#listings).

```json
{
  "deliveries": [
    {
      "id": "6a1106e9f5b4e90cb1d35a01",
      "documentId": "c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f",
      "event": "DOCUMENT_REDACTION_COMPLETE",
      "url": "https://hooks.example.com/philter",
      "status": "PENDING",
      "attempts": 3,
      "lastError": "Webhook responded with HTTP 503",
      "createdAt": "2026-10-10T20:41:07.000Z",
      "updatedAt": "2026-10-10T20:42:44.000Z",
      "nextAttemptAt": "2026-10-10T20:47:44.000Z",
      "deliveredAt": null
    }
  ],
  "total": 1
}
```

* `id` - The delivery's id, sent as `X-Philter-Delivery-Id` with every attempt.
* `status` - `PENDING` (waiting for its next attempt), `PROCESSING` (being sent), `DELIVERED`, or `FAILED` (every attempt failed). See [Retry behavior](#retry-behavior).
* `attempts` and `lastError` - How many attempts have been made, and why the latest one failed.
* `nextAttemptAt` - When a `PENDING` delivery is next attempted.
* `url` - Where the delivery is sent: the webhook URL when the event was queued.

The secret and the event body are not returned. A delivery that is `DELIVERED` or `FAILED` is kept for `WEBHOOK_DELIVERIES_TTL_SECONDS` (default 30 days) and then removed.

### Another user's webhook

An administrator can read, set, remove, or test another user's webhook, or list its deliveries, by adding `owner=<username>` to any of these requests. This requires `ADMIN_CROSS_USER_ACCESS_ENABLED=true`, as for other cross-user access. An owner that does not exist or cannot be reached returns `404 Not Found`. A deactivated user may be named as `owner`.

### Auditing

Setting, removing, and testing a webhook are recorded as `webhook_configured`, `webhook_removed`, and `webhook_tested` [audit events](../../auditing.md), naming the calling user and API key. `webhook_configured` says whether a new secret was set or the existing one kept, and `webhook_tested` records whether the test was accepted and the status code. The URL and secret are not recorded. A refused attempt to set a webhook is not recorded.

## Events

| Event                              | When Philter sends it                                      |
|------------------------------------|------------------------------------------------------------|
| `DOCUMENT_REDACTION_COMPLETE`      | The async worker successfully redacted a document.         |
| `DOCUMENT_REDACTION_FAILED`        | The async worker could not complete the redaction.         |
| `WEBHOOK_TEST`                     | Someone [sent a test event](#send-a-test-event). Sent once, never retried. |

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
