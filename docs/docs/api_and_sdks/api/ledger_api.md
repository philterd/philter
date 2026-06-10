# Redaction Ledger API

The Redaction Ledger API lets you list, view, verify, export, and delete the [redaction ledger](../../redaction/ledgers.md) chains recorded for your account. A ledger chain is the tamper-evident, hash-linked record of the redactions made to a single document (in a [context](../../redaction/contexts.md) that has the ledger enabled).

By default every endpoint is scoped to the user that owns the API key — a regular user only ever sees and acts on their own ledger. An **admin** may act on another user's ledger by passing that user's username as the `owner` query parameter (supported on every endpoint below). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found` — the endpoints never reveal the existence of a user or ledger you are not allowed to access. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

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

* `q` - Optional. Filter to chains whose document id or filename contains this value.
* `owner` - Optional. Admin only. The username of the user whose ledger to list. Defaults to the caller.
* `offset` - Optional. Number of chains to skip (default `0`). Ignored when `q` is supplied.
* `limit` - Optional. Maximum chains to return (default `25`, max `100`). Ignored when `q` is supplied.

Returns `200 OK` with `{ "chains": [ ... ], "total": <count> }`, where `total` is the number of chains you have.

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
  "entries": [
    {
      "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
      "filename": "note.txt",
      "type": "PERSON",
      "token": "John Smith",
      "replacement": "{{{REDACTED-person}}}",
      "startPosition": 11,
      "documentHash": "…",
      "previousHash": "…",
      "hash": "…",
      "timestamp": "2026-06-08T14:11:33.000+00:00"
    }
  ]
}
```

> **Security:** ledger entries contain the **decrypted original token** that was redacted. Access is restricted to the chain's owner.

## Verify a document's ledger chain

| Method | Endpoint                         | Description                                  |
|--------|----------------------------------|----------------------------------------------|
| `GET`  | `/api/ledger/{documentId}/valid` | Check whether the hash chain still verifies.  |

Returns `200 OK` with `{ "documentId": "…", "valid": true }` (the `entries` array is omitted), or `404 Not Found` if no such chain exists. `valid` is `false` if any entry was altered or a link in the chain is broken.

## Export a document's ledger chain

| Method | Endpoint                          | Description                                            |
|--------|-----------------------------------|-------------------------------------------------------|
| `GET`  | `/api/ledger/{documentId}/export` | Export the chain as portable JSON for offline archival. |

Returns `200 OK` with the export document and a `Content-Disposition` header so it can be saved directly to a file, or `404 Not Found` if no such chain exists. Every entry includes its `hash` and `previousHash`, so the exported chain can be re-verified independently of Philter.

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/ledger/7a906866-4fc9-44d6-9bc3-22728b93a602/export" \
  -o ledger-export.json
```

The export body has the shape:

```json
{
  "version": 1,
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "count": 3,
  "entries": [ /* LedgerEntryView objects, as in "Get a document's ledger chain" */ ]
}
```

> **Security:** unlike a context export (token hashes only), a ledger export contains the **decrypted token and replacement** values. Treat it as sensitive and store and transmit it securely.

> **Note:** the ledger is append-only over the API. There is no API endpoint to delete or purge ledger entries; the ledger is governance evidence and is not deletable through the API.
