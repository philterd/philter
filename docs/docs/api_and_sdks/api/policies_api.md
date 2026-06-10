# Policies API

The Policies API provides endpoints for retrieving, uploading, and deleting [policies](../../policies/filter_policies.md).

> **Admin cross-user access:** by default each endpoint operates on the calling user's own policies. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (list, get, create, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

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

### Validation

The policy is validated before it is stored. It must be valid JSON in the native Phileas policy format and contain a non-empty `identifiers` object describing the information to redact. A missing name or an invalid policy is rejected with `400 Bad Request` and a message describing the problem, and nothing is saved.

### Responses

* `201 Created` - The policy was saved.
* `400 Bad Request` - The policy name is missing or the policy is invalid.

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

---

## Policy Version History

Every time a policy is created or updated, Philter automatically retains an immutable snapshot of its content. The following endpoints expose that history and allow any prior revision to be restored.

### List Versions

| Method | Endpoint                                  | Description                                          |
|--------|-------------------------------------------|------------------------------------------------------|
| `GET`  | `/api/policies/{policyName}/versions`     | List retained revisions, most recent first.          |

#### Query Parameters

* `offset` (optional, default: `0`) - Number of entries to skip.
* `limit` (optional, default: `25`, max: `100`) - Maximum number of entries to return.
* `owner` (optional, admin only) - Username of another user whose policy to browse.

#### Response

Returns an array of version summaries. The full policy JSON is **not** included; use [Fetch a Specific Revision](#fetch-a-specific-revision) to retrieve the full content of a particular revision.

```json
[
  { "revision": 3, "capturedTimestamp": "2026-06-09T14:23:00Z", "contentHash": "a1b2c3..." },
  { "revision": 2, "capturedTimestamp": "2026-06-08T09:11:00Z", "contentHash": "d4e5f6..." },
  { "revision": 1, "capturedTimestamp": "2026-06-07T16:04:00Z", "contentHash": "g7h8i9..." }
]
```

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/my-policy/versions
```

### Fetch a Specific Revision

| Method | Endpoint                                          | Description                                      |
|--------|---------------------------------------------------|--------------------------------------------------|
| `GET`  | `/api/policies/{policyName}/versions/{revision}`  | Return the full policy JSON at a given revision. |

The response body has the same shape as [Get a Policy](#get-a-policy).

#### Responses

* `200 OK` - The policy JSON at the requested revision.
* `404 Not Found` - The policy or the requested revision does not exist.

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/my-policy/versions/2
```

### Diff Two Revisions

| Method | Endpoint                               | Description                                      |
|--------|----------------------------------------|--------------------------------------------------|
| `GET`  | `/api/policies/{policyName}/diff`      | Compare two retained revisions.                  |

#### Query Parameters

* `from` (optional) - The revision to diff from.
* `to` (optional) - The revision to diff to.

If both `from` and `to` are omitted, the two most recent retained revisions are compared. If only one is supplied, the request is rejected with `400 Bad Request`.

#### Response

Returns an envelope with the compared revision numbers and an [RFC 6902 JSON Patch](https://jsonpatch.com/) array. Object-level changes (adds, removes, replaces) are reported per field path; array values that differ are reported as a single `replace` at the array path.

```json
{
  "from": 1,
  "to": 3,
  "changes": [
    { "op": "replace", "path": "/identifiers/ssn/ssnFilterStrategies/0/strategy", "value": "MASK" },
    { "op": "add",     "path": "/identifiers/emailAddress", "value": {} }
  ]
}
```

`changes` is an empty array when the two revisions have identical content.

#### Responses

* `200 OK` - Diff produced successfully.
* `400 Bad Request` - The policy name is missing, fewer than two revisions exist (for the default diff), or only one of `from`/`to` was supplied.
* `404 Not Found` - The policy or a requested revision does not exist.

Example requests:

```
# Diff the two most recent revisions
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/policies/my-policy/diff"

# Diff specific revisions
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/policies/my-policy/diff?from=1&to=3"
```

### Rollback to a Prior Revision

| Method  | Endpoint                                   | Description                                                 |
|---------|--------------------------------------------|-------------------------------------------------------------|
| `POST`  | `/api/policies/{policyName}/rollback`      | Restore a prior revision as the new active policy content.  |

Rollback restores the content of the specified revision as a **new** revision — history is never rewritten. The live policy's revision counter is incremented and the restored content is snapshotted. Every rollback is audited as `policy_rolled_back`.

#### Query Parameters

* `revision` (required) - The revision number to restore.
* `owner` (optional, admin only) - Username of another user whose policy to roll back.

#### Responses

* `201 Created` - Rollback succeeded. Body contains the new revision number.
* `404 Not Found` - The policy or the target revision does not exist.
* `409 Conflict` - Managed policies cannot be rolled back.

Example request:

```
curl -X POST -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/policies/my-policy/rollback?revision=1"
```

Example response:

```json
{ "revision": 4 }
```
