# Redaction Ledger API

The Redaction Ledger API lets you list, view, verify, export, and delete the [redaction ledger](../../redaction/ledgers.md) chains recorded for your account. A ledger chain is the tamper-evident, hash-linked record of the redactions made to a single document (in a [context](../../redaction/contexts.md) that has the ledger enabled).

By default every endpoint is scoped to the user that owns the API key, so a regular user only ever sees and acts on their own ledger. An **admin** may act on another user's ledger by passing that user's username as the `owner` query parameter (supported on every endpoint below). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. The endpoints never reveal the existence of a user or ledger you are not allowed to access. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`. A deactivated user may be named as `owner`: deactivation keeps their data, and an admin reaches it as for an active user.

Authenticate with a Bearer token as with the rest of the API:

```
Authorization: Bearer <token>
```

## List ledger chains

| Method | Endpoint      | Description                                              |
|--------|---------------|---------------------------------------------------------|
| `GET`  | `/api/ledger` | List the head (genesis entry) of each document's chain. |

Returns the most recent chains first. Each item is the chain's genesis entry, which identifies the document.

### Query Parameters

* `q` - Optional. Filter to chains whose document id or filename contains this value. Case-insensitive.
* `owner` - Optional. Admin only. The username of the user whose ledger to list. Defaults to the caller.
* `offset` - Optional. Number of chains to skip (default `0`).
* `limit` - Optional. Maximum chains to return (default `25`, max `100`).
* `sort` - Optional. `created` (the default, newest first) or `filename`. With `order`, `asc` or `desc`; see [Listings](../api.md#listings).
* `all_users` - Optional. List every user's chains instead of the caller's; each chain then also has an `owner` field with its owner's username, and `total` counts every user's chains that match `q`. Can be combined with `q`. Requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (disabled by default), as `owner` does; otherwise it returns `404 Not Found`. Cannot be combined with `owner`.

Returns `200 OK` with `{ "chains": [ ... ], "total": <count> }`. `total` is the number of chains the request matched: your whole ledger when `q` is absent, or the number of chains matching `q` when it is present. Paging with `offset` and `limit` applies either way, so `total` always describes the set the returned chains were drawn from.

A chain whose head entry can no longer be read, for example because it no longer decrypts after a key change, is still listed, so the rest of the page is unaffected and `total` is unchanged. Its entry has a `readError` and the fields stored in the clear (`documentId`, `filename`, `type`, the hashes, `timestamp`, and the policy fields), but no `replacement`. Philter logs the failure with the document id.

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger?limit=25"
```

## Get a document's ledger chain

| Method | Endpoint                    | Description                                          |
|--------|-----------------------------|-----------------------------------------------------|
| `GET`  | `/api/ledger/{documentId}`  | Return the full ordered chain for a document.        |

Returns `200 OK` with the chain and whether it currently verifies, or `404 Not Found` if no chain exists for that document id.

```json
{
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "valid": true,
  "hashChainValid": true,
  "signaturesValid": true,
  "signedEntries": 1,
  "unsignedEntries": 0,
  "entries": [
    {
      "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
      "filename": "note.txt",
      "type": "PERSON",
      "replacement": "{{{REDACTED-person}}}",
      "startPosition": 11,
      "documentHash": "…",
      "previousHash": "…",
      "hash": "…",
      "timestamp": "2026-06-08T14:11:33.000Z",
      "policyName": "default",
      "policyVersion": 3,
      "policyContentHash": "…",
      "effectiveHash": "…",
      "signature": "…",
      "signingKeyId": "…"
    }
  ]
}
```

`valid` is `true` only when `hashChainValid` and `signaturesValid` are both `true`; an unsigned entry makes `signaturesValid` `false`. Fields with no value are omitted: `effectiveHash` appears only on entries from asynchronous requests, and `signature` and `signingKeyId` only on signed entries.

> **Security:** reading a chain does not return the original values that were redacted, only the replacements that appear in the redacted document. The originals are carried by the [export](#export-a-documents-ledger-chain), which requires the separate `ledger:export` scope. Access is restricted to the chain's owner either way.

## Verify a document's ledger chain

| Method | Endpoint                         | Description                                  |
|--------|----------------------------------|----------------------------------------------|
| `GET`  | `/api/ledger/{documentId}/valid` | Check whether the hash chain still verifies.  |

Returns `200 OK` with `documentId`, `valid`, `hashChainValid`, `signaturesValid`, `signedEntries`, and `unsignedEntries` (the `entries` array is omitted), or `404 Not Found` if no such chain exists. `valid` is `false` if any entry was altered or a link in the chain is broken.

### A chain that cannot be validated

If the chain cannot be checked at all, for example because an entry can no longer be decrypted or a stored field has the wrong type, both this endpoint and [Get a document's ledger chain](#get-a-documents-ledger-chain) return `200 OK` with `valid` set to `false` and a `validationError`. A chain that cannot be validated is not reported as valid. The response leaves out `hashChainValid`, `signaturesValid`, the entry counts, and `entries`, since those checks did not complete; it does not mean a hash or signature mismatched. Philter logs the failure with the document id.

```json
{
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "valid": false,
  "validationError": "The chain could not be validated, so it is not reported as valid. An entry could not be read or checked."
}
```

A database error while reading the chain is still a `500 Internal Server Error`, since it says nothing about the chain.

## Export a document's ledger chain

| Method | Endpoint                          | Description                                            |
|--------|-----------------------------------|-------------------------------------------------------|
| `GET`  | `/api/ledger/{documentId}/export` | Export the chain as portable JSON for offline archival. |

Returns `200 OK` with the export document and a `Content-Disposition` header so it can be saved directly to a file, or `404 Not Found` if no such chain exists. Every entry includes its `hash` and `previousHash`, and the export encloses the public key of each signing key used, so the chain's linkage and its signatures can be checked independently of Philter. Recomputing the hashes themselves additionally needs the owning account's internal user id, which is part of the hash but is not carried in the export; see [What the Ledger Proves](../../redaction/ledgers.md#what-the-ledger-proves).

If any entry in the chain cannot be read, the chain is not exported, even in part: an export is evidence meant to be re-verified, and one with an entry missing would not verify while looking complete. The response is `422 Unprocessable Content` with a `message` and the `reason` `entry_unreadable`, and the attempt is recorded as `redaction_ledger_exported` with `refused: entry_unreadable` in its details. [Get a document's ledger chain](#get-a-documents-ledger-chain) still shows such a chain, as [one that cannot be validated](#a-chain-that-cannot-be-validated).

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger/7a906866-4fc9-44d6-9bc3-22728b93a602/export" \
  -o ledger-export.json
```

The export body has the shape:

```json
{
  "version": 3,
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "count": 3,
  "entries": [
    {
      "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
      "filename": "note.txt",
      "type": "PERSON",
      "token": "George Washington",
      "replacement": "{{{REDACTED-person}}}",
      "startPosition": 11,
      "documentHash": "…",
      "previousHash": "…",
      "hash": "…",
      "timestamp": "2026-06-08T14:11:33.000Z",
      "policyName": "default",
      "policyVersion": 3,
      "policyContentHash": "…",
      "signature": "…",
      "signingKeyId": "…"
    }
  ],
  "signingKeys": {
    "<signingKeyId>": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----\n"
  }
}
```

Each entry has the fields shown in [Get a document's ledger chain](#get-a-documents-ledger-chain), plus `token`, the original value. Only the first of the three entries is shown.

> **Security:** unlike reading a chain, and unlike a context export (token hashes only), a ledger export contains the **decrypted original values**. That is why it needs `ledger:export` rather than `ledger:read`. Treat the file as sensitive and store and transmit it securely.

### Export contents

Each entry carries its `signature` and the `signingKeyId` of the key that produced it, and the export
embeds those public keys in a `signingKeys` map so it can be verified without contacting the instance
that produced it. The export schema is **version 3**.

## Delete a document's ledger chain

| Method   | Endpoint                   | Description                            |
|----------|----------------------------|----------------------------------------|
| `DELETE` | `/api/ledger/{documentId}` | Permanently delete a document's chain. |

Removes every entry for the document. Returns `200 OK`.

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger/7a906866-4fc9-44d6-9bc3-22728b93a602"
```

## Purge old ledger entries

| Method   | Endpoint      | Description                                     |
|----------|---------------|-------------------------------------------------|
| `DELETE` | `/api/ledger` | Delete your chains older than a number of days. |

The ledger is kept indefinitely by default (see [How and When Ledger Entries Are Deleted](../../redaction/ledgers.md#how-and-when-ledger-entries-are-deleted)); this endpoint is how you prune stale entries on demand.

### Query Parameters

* `older_than_days` (required) - Delete complete chains whose completion time and newest entry are older than this many days. Must be zero or greater (`0` purges completed chains; unfinished chains remain).
* `owner` - Optional. Admin only. The username of the user whose entries to purge. Defaults to the caller.

Returns `200 OK` with a `message` such as `"Deleted 42 ledger entries in 3 completed chains older than 90 days."`, or `400 Bad Request` if `older_than_days` is missing, is not a number, or is negative.

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger?older_than_days=90"
```

### Requirements for both deletion endpoints

Deletion is the only destructive operation in this API, and it is gated more tightly than the rest of it.

* **Administrator only.** A valid API key belonging to a non-admin receives `403 Forbidden`, even for its own ledger. This differs from the `404 Not Found` used elsewhere in this API: that code exists to avoid revealing whether another user or resource exists, and there is nothing to conceal about your own ledger.
* **`LEDGER_DELETION_ENABLED=true` is required.** It is `false` by default, so a deployment that has not opted in cannot delete ledger evidence at all and both endpoints return `403 Forbidden`. See [Settings](../../settings.md).
* **Deleting another user's ledger needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` as well**, in addition to the above, via the `owner` parameter.
* **Legal holds still apply.** If an active [legal hold](../../redaction/legal_holds.md) covers the evidence, the request returns `423 Locked` with the blocking hold references and nothing is deleted. A purge is blocked in its entirety if the user has any active hold, because an age-based purge cannot selectively skip held documents.
* **Every deletion is audited** as `redaction_ledger_deleted`, and every hold-blocked attempt as `legal_hold_blocked_deletion`. Deletion removes ledger entries; it never removes the audit record that the deletion happened.

Deletion always operates on whole document chains, never on individual entries within a chain, so a chain that remains is always complete and still verifies.

Concurrent hold/evidence operations for the same owner return HTTP 409. Interrupted operations retain a guard until [safe recovery](../../redaction/legal_holds.md#concurrent-operations-and-recovery); active holds continue to block deletion with HTTP 423.

Async entries expose `effectiveHash`, which binds the captured resolved configuration into each entry hash. See the [canonical field order](../../redaction/ledgers.md#what-the-ledger-proves) when implementing independent verification. Entries without a captured configuration encode this field as unset.

Manual chain deletion returns `409 Conflict` while publication is open, writing, or failed and requires recovery. Completed chains retain a deletion marker, preventing later appends or reuse of the document ID. A missing chain returns 404.
