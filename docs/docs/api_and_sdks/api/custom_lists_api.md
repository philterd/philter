# Custom Lists API

The Custom Lists API provides endpoints for retrieving, creating, and deleting custom lists.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own lists. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (list, get, create, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.

## Get List Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/lists` | Get the names of all custom lists. |

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose lists to get. Requires cross-user access to be enabled; otherwise it returns `404 Not Found`.
* `all_users` (optional, default: `false`) - List every user's custom lists instead of the caller's. Each item is then an object with the list's `name` and its `owner`'s username. Requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (disabled by default), as `owner` does; otherwise it returns `404 Not Found`. Cannot be combined with `owner`.
* `offset` (optional, default: `0`) and `limit` (optional, default: `25`, max `100`) - Page through the lists. These apply only with `all_users`; without it, every one of the caller's lists is returned.

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/lists
```

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
  "items": [
    "item1",
    "item2"
  ]
}
```

## Create or Update a List

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `POST` | `/api/lists/{name}` | Create or update a custom list. |

### Query Parameters

* `description` (optional) - A description of the custom list.

### Request Body

A JSON array of strings containing the items for the list.

### Responses

* `201 Created` - A new list was created.
* `200 OK` - An existing list with the same name was overwritten.

Example request:

```
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/lists/my-list?description=My%20description" -d '["item1", "item2"]'
```

## Delete a List

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/lists/{name}` | Delete a custom list, where {name} is the name of the list to delete. |

Example request:

```
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/lists/my-list
```
