# Policies API

The Policies API provides endpoints for retrieving, uploading, and deleting [policies](../../policies/filter_policies.md).

> **Admin cross-user access:** by default each endpoint operates on the calling user's own policies. An **admin** may target another user by adding an `owner=<email>` query parameter to any endpoint (list, get, create, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.


## Get Policy Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/policies` | Get the names of policies (paginated). |

### Query Parameters

* `offset` (optional, default: `0`) - The number of policy names to skip.
* `limit` (optional, default: `25`) - The maximum number of policy names to return. The response is paginated, so request successive pages with `offset` to retrieve all names.

Example request:

```
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/policies?offset=0&limit=100"
```

## Get a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `GET` | `/api/policies/{policyName}` | Get the content of a policy, where {policyName} is the name of the policy to get. |

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/my-policy
```

Example response:

```
{
  "name": "just-phone-numbers",
  "ignored": [
  ],
  "identifiers": {
    "dictionaries": [
    ],
    "phoneNumber": {
      "phoneNumberFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

## Save a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `POST` | `/api/policies` | Save a policy. If a policy with this name already exists it will be overwritten.|

### Query Parameters

* `name` - The name of the policy to save.

Example request:

```
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies?name=my-policy" -d @policy.json
```

## Delete a Policy

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/policies/{policyName}` | Delete a policy, where {policyName} is the name of the policy to delete. |

Example request:

```
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/my-policy
```
