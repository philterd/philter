# Contexts API

The Contexts API provides endpoints for retrieving, creating, and deleting contexts, and for listing, exporting, and importing the token-to-replacement mappings within a context.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own contexts. Because context names are unique only per user, an **admin** identifies another user's context by adding an `owner=<username>` query parameter. A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.

## Get Context Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/contexts` | Get the names of contexts (paginated). |

### Query Parameters

* `offset` (optional, default: `0`) - The number of context names to skip.
* `limit` (optional, default: `25`) - The maximum number of context names to return. The response is paginated, so request successive pages with `offset` to retrieve all names.
* `owner` (optional) - The username of the user whose contexts to list. When omitted, the caller's own contexts are listed. Supplying an `owner` other than yourself requires admin privileges and cross-user access being enabled (`ADMIN_CROSS_USER_ACCESS_ENABLED=true`; disabled by default); otherwise it receives `404 Not Found`.

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/contexts?offset=0&limit=100"
```

Example response:

```json
{
  "contexts": [
    "default",
    "my-context"
  ]
}
```

## Get Context Details

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `GET` | `/api/contexts/{name}` | Get the details of a context, where {name} is the name of the context to get. |

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/contexts/my-context
```

Example response:

```json
{
  "size": 125
}
```

## Create a Context

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `POST` | `/api/contexts` | Create a new context. |

### Query Parameters

* `name` (required) - The name of the context to create. Context names are **unique per user**: if you already have a context with this name the request is rejected with `409 Conflict`. A name you use does not prevent another user from using the same name.
* `entity_type_disambiguation` (optional, default: `false`) - Whether to enable entity type disambiguation for this context.
* `ledger` (optional, default: `false`) - Whether to enable the redaction ledger for this context.

Example request:

```bash
curl -X POST -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/contexts?name=my-context&entity_type_disambiguation=true"
```

## Update a Context

| Method | Endpoint               | Description                                                                   |
|--------|------------------------|-------------------------------------------------------------------------------|
| `PUT`  | `/api/contexts/{name}` | Update the `entity_type_disambiguation` and `ledger` flags on a context.      |

### Query Parameters

* `entity_type_disambiguation` (optional, default: `false`) - Enable entity type disambiguation.
* `ledger` (optional, default: `false`) - Enable the redaction ledger.

Example request:

```bash
curl -X PUT -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/contexts/my-context?entity_type_disambiguation=false&ledger=true"
```

Returns `200 OK` on success, `404 Not Found` if no context with that name exists for the calling user.

## Delete a Context

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/contexts/{name}` | Delete a context, where {name} is the name of the context to delete. |

A delete is **rejected with `409 Conflict`** if any asynchronously-submitted document referencing the context is still pending or processing. Either wait for the jobs to finish (poll the [Documents API](documents_api.md)) or delete the pending jobs first.

Example request:

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/contexts/my-context
```

## List Context Entries

| Method | Endpoint                          | Description                                          |
|--------|-----------------------------------|------------------------------------------------------|
| `GET`  | `/api/contexts/{name}/entries`    | Paginated list of token-to-replacement mappings.     |

### Query Parameters

* `offset` (optional, default: `0`)
* `limit` (optional, default: `25`, max: `100`)

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/contexts/my-context/entries?offset=0&limit=25"
```

Example response:

```json
{
  "entries": [
    {
      "id": "6a1101d50737f1edfcf70d77",
      "replacement": "{{{REDACTED-person}}}",
      "filterType": "PERSON",
      "reads": 14,
      "timestamp": "2026-05-22T20:00:00.000+00:00"
    }
  ],
  "total": 1
}
```

The original token is never returned by this endpoint; only its SHA-256 hash is stored.

## Empty a Context

| Method   | Endpoint                          | Description                                |
|----------|-----------------------------------|--------------------------------------------|
| `DELETE` | `/api/contexts/{name}/entries`    | Remove all entries from a context.         |

Removes every token-to-replacement mapping in the context but leaves the context itself in place. Returns `200 OK` on success, `404 Not Found` if the context does not exist.

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" \
  https://localhost:8080/api/contexts/my-context/entries
```

## Delete a Single Entry

| Method   | Endpoint                                       | Description                            |
|----------|------------------------------------------------|----------------------------------------|
| `DELETE` | `/api/contexts/{name}/entries/{entryId}`       | Remove one entry by its id.            |

`entryId` is the `id` returned in the `entries` listing. Returns `200 OK` if deleted, `404 Not Found` if no such entry exists for the calling user, `400 Bad Request` if `entryId` is not a valid id.

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" \
  https://localhost:8080/api/contexts/my-context/entries/6a1101d50737f1edfcf70d77
```

## Export a Context's Mapping Table

| Method | Endpoint                                | Description                                                  |
|--------|-----------------------------------------|-------------------------------------------------------------|
| `GET`  | `/api/contexts/{name}/entries/export`   | Export the complete mapping table for a context as JSON.    |

Exports every token-to-replacement mapping in the context in a portable JSON form that can be re-imported into another context, account, or environment (see [Import](#import-a-mapping-table-into-a-context) below) to keep pseudonymization consistent across runs.

**Authorization:** only the user that **created** the context or an **admin** may export it. Any other caller receives `404 Not Found`, which is also returned when the context does not exist — the endpoint does not reveal the existence of a context you are not allowed to access.

### Query Parameters

* `owner` (optional) - The username of the context's owner. Because context names are unique only per user, an admin uses this to identify another user's context unambiguously. When omitted, the export applies to the caller's own context with the given name. Supplying an `owner` other than yourself requires admin privileges and cross-user access being enabled (`ADMIN_CROSS_USER_ACCESS_ENABLED=true`; disabled by default); otherwise it receives `404 Not Found`.

Returns `200 OK` with the export document. The response is sent with a `Content-Disposition` header so it can be saved directly to a file.

> **Security:** the export contains only the SHA-256 **hash** of each original token (never the original value) along with its replacement. Because the hash is what redaction looks up, this is sufficient to reproduce consistent replacements without exposing the underlying sensitive data. The export still reveals the replacement values, so treat it as sensitive and transmit/store it securely.

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/contexts/my-context/entries/export" -o my-context-export.json
```

Example response body:

```json
{
  "version": 1,
  "context": "my-context",
  "count": 2,
  "entries": [
    {
      "tokenHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "replacement": "David Jones",
      "filterType": "PERSON",
      "replacementUuid": false
    },
    {
      "tokenHash": "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
      "replacement": "842-436-2042",
      "filterType": "PHONE_NUMBER",
      "replacementUuid": false
    }
  ]
}
```

## Import a Mapping Table into a Context

| Method | Endpoint                                | Description                                              |
|--------|-----------------------------------------|---------------------------------------------------------|
| `POST` | `/api/contexts/{name}/entries/import`   | Import token-to-replacement mappings into a context.    |

Accepts a document in the same format produced by the [export](#export-a-contexts-mapping-table) endpoint and inserts its mappings into the named context. The context must already exist (create it first with `POST /api/contexts`).

**Authorization:** only the user that **created** the context or an **admin** may import into it. Any other caller receives `404 Not Found`.

### Query Parameters

* `on_conflict` (optional, default: `skip`) - What to do when an incoming token already exists in the target context:
    * `skip` - leave the existing replacement unchanged (preserves replacements already learned in this context).
    * `overwrite` - replace the existing replacement with the imported one.
* `owner` (optional) - The username of the context's owner. Because context names are unique only per user, an admin uses this to identify another user's context unambiguously. When omitted, the import applies to the caller's own context with the given name. Supplying an `owner` other than yourself requires admin privileges and cross-user access being enabled (`ADMIN_CROSS_USER_ACCESS_ENABLED=true`; disabled by default); otherwise it receives `404 Not Found`.

### Behavior and Validation

* The payload is fully validated before anything is written, so a malformed entry cannot leave a partially-imported table. Each entry must have a valid 64-character hex `tokenHash` and a non-empty `replacement`.
* Imported entries start with a read count of zero. As with normal redaction, imports honor the context's `MAX_CONTEXT_SIZE` and may evict least-read entries when the context is full.

Returns `200 OK` with a summary, `400 Bad Request` for an invalid `on_conflict` value or malformed payload, and `404 Not Found` if the context does not exist or the caller is not authorized to access it.

Example request:

```bash
curl -X POST -k -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  --data-binary @my-context-export.json \
  "https://localhost:8080/api/contexts/my-context/entries/import?on_conflict=skip"
```

Example response:

```json
{
  "total": 2,
  "inserted": 2,
  "overwritten": 0,
  "skipped": 0
}
```

## Capacity

Each context is bounded by `MAX_CONTEXT_SIZE` (default 10,000 entries; overridable via the [`MAX_CONTEXT_SIZE` environment variable](../../settings.md)). When the limit is reached, the least-read entry is evicted before a new one is inserted (ties broken by oldest). Disambiguation vector storage is similarly bounded by `MAX_VECTORS_PER_CONTEXT` (default 100,000, FIFO eviction).
