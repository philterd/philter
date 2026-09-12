# Audit Log API

The Audit Log API returns the events Philter records in its [audit log](../../auditing.md) as JSON, so the log can be shipped to a SIEM, pulled for a compliance review, or alerted on without a connection to Philter's MongoDB.

The audit log covers the whole deployment rather than a single account, so reading it requires an **administrator** as well as an API key holding the `audit:read` scope. A key without the scope receives `403 Forbidden` naming the missing scope; a key whose owner is not an administrator receives `403 Forbidden`.

Reading the audit log is itself audited: every call records an `audit_log_retrieved` event naming the principal that read it, the filters applied, and how many events matched.

Authenticate with a Bearer token as with the rest of the API:

```
Authorization: Bearer <token>
```

## List audit events

| Method | Endpoint     | Description                        |
|--------|--------------|------------------------------------|
| `GET`  | `/api/audit` | List audit events, most recent first. |

### Query Parameters

* `event` - Optional. Return only events of this type, such as `policy_deleted`. The value must be one of the event names listed in [Auditing](../../auditing.md); any other value is rejected with `400 Bad Request` rather than returning an empty page, so a typo does not read as "this never happened".
* `from` - Optional. Return only events at or after this time. An ISO-8601 instant, such as `2026-09-01T00:00:00Z`. Inclusive.
* `to` - Optional. Return only events strictly before this time, in the same format. Exclusive, so a day's events are `from` that day `to` the next.
* `owner` - Optional. Return only events whose acting principal is this user, named by username. Resolved by the usual rules: a username that does not exist, or one the caller may not reach, returns `404 Not Found`. Note that some events record the affected entity rather than the acting principal in `api_key_id` and so are not returned by an `owner` filter; an unfiltered listing always returns them.
* `offset` - Optional. Number of events to skip (default `0`).
* `limit` - Optional. Maximum events to return (default `25`, max `100`).

Returns `200 OK` with `{ "events": [ ... ], "total": <count> }`. `total` is the number of events matching the filters, so paging with `offset` and `limit` always describes the set the returned events were drawn from.

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/audit?event=policy_deleted&from=2026-09-01T00:00:00Z&limit=50"
```

```json
{
  "events": [
    {
      "timestamp": "2026-09-12T16:12:06.481+00:00",
      "event": "policy_deleted",
      "requestId": "b0e1f6c2-1d3a-4f88-9a7e-2c5d0a6f1b34",
      "apiKeyId": "6aa5792a403075186a843960",
      "associatedObject": "6aa57a01403075186a843971",
      "clientIpAddress": "192.168.64.1",
      "details": "policy: audit-probe-policy, source: api"
    }
  ],
  "total": 1
}
```

Every field except `timestamp` and `event` may be absent for an event that did not record it. `apiKeyId` is the acting principal, `associatedObject` the entity the action concerned, and `details` a short, non-sensitive description. Audit events never carry the redacted values themselves; see [Auditing](../../auditing.md) for the full field reference and the list of events.

## Errors

| Status | When |
|--------|------|
| `400 Bad Request` | A `from` or `to` value is not an ISO-8601 instant, `from` is after `to`, or `event` is not an event type Philter emits. |
| `401 Unauthorized` | The `Authorization` header is absent or the API key is not recognized. |
| `403 Forbidden` | The key does not hold `audit:read`, or the caller is not an administrator. |
| `404 Not Found` | The `owner` does not exist, or the caller may not reach it. The two are indistinguishable, so an `owner` value cannot be used to discover accounts. |

## See also

* [Auditing](../../auditing.md) for what Philter records, the field reference, and the CSV export.
* [API Keys](../../account/api_keys.md) for scopes and how to grant `audit:read`.
