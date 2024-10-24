# Policies API

The Policies API provides endpoints for retrieving, uploading, and deleting [policies](../../policies/filter_policies.md).

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.


## Get Policy Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/policies` | Get the names of all policies. |


Example request:

```
curl -k https://localhost:8080/api/policies
```

## Get a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `GET` | `/api/policies/{policyName}` | Get the content of a policy, where {policyName} is the name of the policy to get. |

Example request:

```
curl -k https://localhost:8080/api/policies/my-policy
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

## Upload a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `PUT` | `/api/policies/{policyName}` | Upload a policy, where {policyName} is the name of the policy to get. If a policy with this name already exists it will be overwritten.|

Example request:

```
curl -X PUT -H "Content-Type: application/json" -k https://localhost:8080/api/profiles/my-profile -d @policy.json
```

## Delete a Policy

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/policies/{policyName}` | Delete a policy, where {policyName} is the name of the policy to delete. |

Example request:

```
curl -X DELETE -k https://localhost:8080/api/policies/exprofile
```
