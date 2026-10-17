# PII Counts API

The PII Counts API returns the PII type counts Philter records for [differential-privacy reporting with Philter Diffuse](../../diffuse.md), so the counts can be read with an API key instead of a connection to Philter's database. It returns **counts only**, never any PII.

Philter records counts only while the `diffuseCountsEnabled` [admin setting](settings_api.md) is on. It is off by default.

The counts cover the whole deployment rather than a single account, kept separately for each user's context, so reading them requires an **administrator** as well as an API key holding the `pii-counts:read` scope. A key without the scope receives `403 Forbidden` naming the missing scope; a key whose owner is not an administrator receives `403 Forbidden`.

Every successful call records a `pii_counts_retrieved` event in the [audit log](../../auditing.md), naming the principal that read the counts, the filters applied, and how many entries matched.

Authenticate with a Bearer token as with the rest of the API:

```
Authorization: Bearer <token>
```

## List PII counts

| Method | Endpoint          | Description                                        |
|--------|-------------------|----------------------------------------------------|
| `GET`  | `/api/pii-counts` | List the recorded counts, most recent day first unless sorted otherwise. |

### Query Parameters

* `from` - Optional. Return only counts for this UTC day or later, as a date (`YYYY-MM-DD`). Inclusive.
* `to` - Optional. Return only counts for this UTC day or earlier, in the same format. Inclusive.
* `context` - Optional. Return only counts for contexts of this name. Without [cross-user access](../../settings.md#api-access), only the caller's own.
* `owner` - Optional. Return only this user's counts, by username. Another user's requires an administrator **and** `ADMIN_CROSS_USER_ACCESS_ENABLED=true`; otherwise `404 Not Found`.
* `sort` - Optional. `day` (the default), `context`, or `documents` (how many redactions found any PII). Ties are ordered by day, most recent first, then by user and context.
* `order` - Optional. `asc` or `desc`. The default is `desc` for `day` and `asc` otherwise.
* `offset` - Optional. Number of entries to skip (default `0`).
* `limit` - Optional. Maximum entries to return (default `25`, max `100`).

A date that is not `YYYY-MM-DD`, a year outside 1 to 9999, a `from` after `to`, or a `sort` or `order` not listed above, returns `400 Bad Request`. A refused call returns no counts and records no `pii_counts_retrieved` event.

Returns `200 OK` with `{ "recordingEnabled": <boolean>, "counts": [ ... ], "total": <count> }`. There is one entry per user's context and day, ordered by day, most recent first: two users' contexts of the same name are separate entries. `total` is the number of entries matching the filters, so paging with `offset` and `limit` always describes the set the returned entries were drawn from.

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/pii-counts?from=2026-09-01&to=2026-09-30&context=claims-intake"
```

```json
{
  "recordingEnabled": true,
  "counts": [
    {
      "id": "9b2f6c0d4e8a1f3b5c7d9e1f2a4b6c8d",
      "owner": "jordan",
      "context": "claims-intake",
      "day": "2026-09-30",
      "counts": { "SSN": 1240, "EMAIL_ADDRESS": 8900, "PERSON": 9500 },
      "totalDocuments": 10000,
      "updatedAt": "2026-09-30T18:42:10.000Z"
    }
  ],
  "total": 1
}
```

* `id` - Identifies this user's context and day, the same on every read, so a client paging through the counts can tell entries apart without their names.
* `owner` - The username of the user whose redactions these are.
* `context` - The context the redactions ran in.

`owner` and `context` are given for the caller's own counts, and for other users' only when `ADMIN_CROSS_USER_ACCESS_ENABLED` is `true`; otherwise they are absent, while the counts are still returned, so totals across the deployment are complete without revealing other users' context names. A listing that names other users' counts is also recorded as an `admin_cross_user_access` audit event.

* `day` - The UTC day the redactions ran on.
* `counts` - For each PII type, how many redactions that day found at least one of it. A redaction adds at most one to each type, however many of that type it found.
* `totalDocuments` - How many redactions that day found any PII. Redactions that found no PII are not counted.
* `updatedAt` - When a redaction was last counted.

`recordingEnabled` says whether Philter is recording counts now. When it is `false`, an empty listing does not mean that no PII was found, only that nothing is being recorded. Turning recording off is not an error: counts recorded while it was on are still returned.

## See Also

* [Differential-Privacy Reporting with Philter Diffuse](../../diffuse.md)
* [Settings API](settings_api.md)
* [Auditing](../../auditing.md)
