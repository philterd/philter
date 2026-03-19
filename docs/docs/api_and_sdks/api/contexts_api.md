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
* `coref` (optional, default: `false`) - Whether to enable coreference resolution for this context.
* `disambiguation` (optional, default: `false`) - Whether to enable disambiguation for this context.

Example request:

```bash
curl -X POST -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/contexts?name=my-context&coref=true"
```

## Delete a Context

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/contexts/{name}` | Delete a context, where {name} is the name of the context to delete. |

Example request:

```bash
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/contexts/my-context
```
