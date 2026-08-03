# Legal Holds API

The legal holds API sets, lists, and releases [legal holds](../../redaction/legal_holds.md): named, audited instructions that block deletion of redaction evidence until the hold is released.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own holds. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (set, list, get, release). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

All endpoints require authentication with a Bearer token. See [API Keys](../../account/api_keys.md).

## Set a Hold

| Method | Endpoint      | Description                                            |
|--------|---------------|--------------------------------------------------------|
| `POST` | `/api/holds`  | Create a named hold that blocks deletion of evidence.  |

### Request Body

| Field | Required | Description |
|-------|----------|-------------|
| `reference` | Yes | Hold identifier, unique for the calling user (for example `LIT-2026-001`). Arbitrary text; Philter does not interpret it. |
| `scopeType` | Yes | `document_chain` to protect one document's ledger chain, or `user` to protect all of a user's evidence. |
| `scopeValue` | Yes | The document id (for `document_chain`) or the target user's id (for `user`). |
| `reason` | No | Free-text description of why the hold was set. |

### Query Parameters

* `owner` (optional, admin only) - Username of the user whose evidence the hold protects.

### Responses

* `201 Created` - The hold was set and is now active. The body contains the hold details.
* `400 Bad Request` - A required field is missing or `scopeType` is not recognized.
* `409 Conflict` - A hold with this reference already exists for the user.

Example request:

```bash
curl -k -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  "https://localhost:8080/api/holds" \
  -d '{"reference":"LIT-2026-001","scopeType":"document_chain","scopeValue":"doc-abc123","reason":"Preserve pending resolution"}'
```

## List Holds

| Method | Endpoint     | Description                                          |
|--------|--------------|-------------------------------------------------------|
| `GET`  | `/api/holds` | List active holds, most recently set first.          |

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose holds to list.
* `offset` (optional, default `0`) - Number of holds to skip.
* `limit` (optional, default `25`) - Maximum number of holds to return.

### Responses

* `200 OK` - A JSON array of holds, ordered by set date descending.

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/holds?offset=0&limit=25"
```

## Get a Hold

| Method | Endpoint                   | Description                             |
|--------|----------------------------|------------------------------------------|
| `GET`  | `/api/holds/{reference}`   | Retrieve a single hold by its reference. |

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose hold to retrieve.

### Responses

* `200 OK` - The hold details.
* `404 Not Found` - No hold with that reference exists for the user.

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/holds/LIT-2026-001"
```

## Release a Hold

| Method   | Endpoint                 | Description                      |
|----------|--------------------------|-----------------------------------|
| `DELETE` | `/api/holds/{reference}` | Release (delete) the named hold. |

Once released, evidence previously covered by this hold becomes eligible for deletion if no other hold remains. Releasing a hold does not delete any ledger data. Every release is recorded in the [audit log](../../auditing.md) as `legal_hold_released`.

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose hold to release.

### Responses

* `200 OK` - The hold was released.
* `404 Not Found` - No hold with that reference exists for the user.

Example request:

```bash
curl -k -X DELETE -H "Authorization: Bearer <token>" "https://localhost:8080/api/holds/LIT-2026-001"
```

## Blocked Deletions

While any hold covers the target evidence, deletion endpoints return `423 Locked` and list the blocking hold references in the response body. The attempt is recorded in the audit log as `legal_hold_blocked_deletion`. See [Legal Holds](../../redaction/legal_holds.md#how-holds-block-deletions) for which operations are checked.
