# Contexts API

The Contexts API provides endpoints for retrieving, creating, and deleting contexts.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.

## Get Context Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/contexts` | Get the names of all contexts. |

Example request:

```bash
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/contexts
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

* `name` (required) - The name of the context to create.
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

The `default` context cannot be deleted.

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

## Capacity

Each context is bounded by `MAX_CONTEXT_SIZE` (default 10,000 entries; overridable via the [`MAX_CONTEXT_SIZE` environment variable](../../settings.md)). When the limit is reached, the least-read entry is evicted before a new one is inserted (ties broken by oldest). Disambiguation vector storage is similarly bounded by `MAX_VECTORS_PER_CONTEXT` (default 100,000, FIFO eviction).
