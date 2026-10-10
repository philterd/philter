# Custom Lists API

The Custom Lists API provides endpoints for retrieving, creating, replacing, and deleting custom lists.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own lists. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (list, get, create, replace, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`. A deactivated user may be named as `owner`: deactivation keeps their data, and an admin reaches it as for an active user.

> Philter serves HTTPS on port 8080 with a generated self-signed certificate by default, so the `curl` examples on this page pass `-k`. See the [TLS](../../settings.md#tls) settings to supply your own certificate.

## Get List Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/lists` | List the custom lists, each with its name, description, and number of items. |

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose lists to get. Requires cross-user access to be enabled; otherwise it returns `404 Not Found`.
* `all_users` (optional, default: `false`) - List every user's custom lists instead of the caller's. Each list then also has its `owner`'s username. Requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (disabled by default), as `owner` does; otherwise it returns `404 Not Found`. Cannot be combined with `owner`.
* `offset` (optional, default: `0`) and `limit` (optional, default: `25`, max `100`) - Page through the lists.
* `q` (optional) - Only lists whose name contains `q`, ignoring case.
* `sort` (optional, default: `name`) and `order` (optional, default: `asc`) - See [Listings](../api.md#listings).

Returns the lists on the requested page in `lists` and `total`, how many there are across every page.

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/lists
```

Example response:

```json
{
  "lists": [
    {
      "name": "my-list",
      "description": "My description",
      "size": 2
    }
  ],
  "total": 1
}
```

* `name` - The list's name.
* `description` - The list's description, or an empty string if it has none.
* `size` - The number of items in the list.
* `owner` - The username of the list's owner. Present only with `all_users`.

## Get a List

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `GET` | `/api/lists/{name}` | Get the content of a list, where {name} is the name of the list to get. |

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/lists/my-list
```

Example response:

```json
{
  "lists": [
    "item1",
    "item2"
  ],
  "description": "My description"
}
```

* `lists` - The list's items.
* `description` - The list's description, or an empty string if it has none.

## Create a List

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `POST` | `/api/lists/{name}` | Create a custom list. A name you already use is refused; to change an existing list, [replace it](#replace-a-list). |

### Query Parameters

* `description` (optional) - A description of the custom list. Left out, the list has none.

### Request Body

A JSON array of strings containing the items for the list. A list holds at most 100 items of at most 50 characters each. Items are trimmed; `null` and empty items are dropped.

The name is part of the path, so it cannot contain `/`, `\`, `;`, `%`, or control characters, and cannot be `.` or `..`. Other text, including spaces and periods, is allowed when percent-encoded.

### Responses

* `201 Created` - The list was created.
* `400 Bad Request` - The name is empty or breaks the rule above, the list has no items (`List items cannot be empty.`), there are too many items, or an item is too long. The body carries a `message`.
* `404 Not Found` - The owner does not exist or may not be reached.
* `409 Conflict` - You already have a list with this name, including one created by a concurrent request. Nothing is changed. The body carries a `message` and the `reason` `list_exists`.

Example request:

```
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/lists/my-list?description=My%20description" -d '["item1", "item2"]'
```

Example `409` response:

```json
{
  "message": "A list with this name already exists.",
  "reason": "list_exists"
}
```

## Replace a List

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------|
| `PUT` | `/api/lists/{name}` | Replace the items of an existing custom list. |

### Query Parameters

* `description` (optional) - Left out, the list keeps its description. An empty value (`description=`) clears it.

### Request Body

A JSON array of strings containing the complete new items for the list, with the same limits as [create](#create-a-list).

### Responses

* `200 OK` - The list was replaced.
* `400 Bad Request` - The list has no items (`List items cannot be empty.`), there are too many items, or an item is too long. The body carries a `message`.
* `404 Not Found` - There is no such list, or the owner does not exist or may not be reached. The body carries a `message`.

Example request:

```
curl -X PUT -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/lists/my-list" -d '["item1", "item2", "item3"]'
```

## Delete a List

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/lists/{name}` | Delete a custom list, where {name} is the name of the list to delete. |

Example request:

```
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/lists/my-list
```

### Responses

* `204 No Content` - The list was deleted.
* `404 Not Found` - There is no such list, or the owner does not exist or may not be reached. The `reason` is `not_found`.
