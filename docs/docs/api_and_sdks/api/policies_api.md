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
* `all_users` (optional, default: `false`) - List every user's policies instead of the caller's. Each item is then an object with the policy's `name` and its `owner`'s username. Managed policies are not included. Requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (disabled by default), as `owner` does; otherwise it returns `404 Not Found`. Cannot be combined with `owner`.
* `managed` (optional, default: `false`) - List the names of the built-in [managed policies](../../policies/sample_policies.md#managed-policies) instead of the caller's. Cannot be combined with `owner` or `all_users`.

Example request:

```
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/policies?offset=0&limit=100"
```

Example response with `all_users=true`:

```json
[
  { "name": "default", "owner": "alice" },
  { "name": "default", "owner": "bob" }
]
```

## Get a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `GET` | `/api/policies/{policyName}` | Get the content of a policy, where {policyName} is the name of the policy to get. |

A name beginning with `managed_` returns that [managed policy](../../policies/sample_policies.md#managed-policies). The response is the policy itself; its description and notes are returned by [Get a Policy's Details](#get-a-policys-details).

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

* `name` (required) - The name of the policy to save.
* `description` (optional) - Up to 200 characters. When updating a policy, leaving it out keeps the current description.
* `notes` (optional) - Up to 1000 characters. When updating a policy, leaving it out keeps the current notes.

### Validation

The policy is validated before it is stored. It must be valid JSON in the native Phileas policy format and contain a non-empty `identifiers` object describing the information to redact. A missing name or an invalid policy is rejected with `400 Bad Request` and a message describing the problem, and nothing is saved.

### Responses

* `201 Created` - The policy was saved.
* `400 Bad Request` - The policy name is missing or invalid, the policy is invalid, or the description or notes are too long. A name may be up to 50 characters of letters, digits, `_` and `-`, and may not begin with `managed_`.
* `409 Conflict` - The named policy is a managed policy and cannot be overwritten.

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

## Compile a PhiSQL Policy

| Method | Endpoint                | Description                                                    |
| ------ |-------------------------|----------------------------------------------------------------|
| `POST` | `/api/policies/compile` | Compile [PhiSQL](../../policies/phisql.md) source into a native policy. |

The request body is PhiSQL source sent as `text/plain`. The response carries the policy name and description from the `POLICY` declaration and the compiled policy. Compiling does not save anything: post the returned `policy` to [`POST /api/policies`](#save-a-policy) to store it.

The compiled policy is validated against the policy schema before it is returned, so a policy that compiles but would be rejected on save fails here instead.

### Responses

* `200 OK` - The policy compiled successfully.
* `400 Bad Request` - The source failed to parse or compile, or the compiled policy failed validation. The body carries the compiler's message.

Example request:

```
curl -X POST -k -H "Authorization: Bearer <token>" -H "Content-Type: text/plain" \
  https://localhost:8080/api/policies/compile \
  --data-binary 'POLICY ssn_only;
REDACT SSN WITH MASK;'
```

Example response:

```json
{
  "name": "ssn_only",
  "policy": {
    "identifiers": {
      "ssn": {
        "ssnFilterStrategies": [
          { "strategy": "MASK" }
        ]
      }
    }
  }
}
```

The `description` field is present when the source declares one. `name` is `null` when the source has no `POLICY` declaration, in which case supply a name yourself when saving.

Example error response:

```json
{"message": "Unknown entity type: NOT_A_THING"}
```

See [Authoring Policies with PhiSQL](../../policies/phisql.md) for the language and the compile-then-save workflow.

---

## Get a Policy's Details

| Method | Endpoint                             | Description                                      |
|--------|--------------------------------------|--------------------------------------------------|
| `GET`  | `/api/policies/{policyName}/details` | Get everything about a policy except the policy itself. |

Requires `policies:read`. Works for managed policies too. Supports `owner` as the other policy endpoints do.

```json
{
  "name": "court",
  "description": "Federal court filings",
  "notes": "Reviewed with the clerk's office.",
  "revision": 3,
  "managed": false,
  "created": "2026-10-01T14:03:11.000Z",
  "lastUpdated": "2026-10-05T09:12:40.000Z"
}
```

## Set a Policy's Description and Notes

| Method | Endpoint                             | Description                                      |
|--------|--------------------------------------|--------------------------------------------------|
| `PUT`  | `/api/policies/{policyName}/details` | Set a policy's description and notes.            |

Requires `policies:write`. Returns the policy's details.

```json
{
  "description": "Federal court filings",
  "notes": ""
}
```

A field left out is left as it is, and an empty value clears it. The description may be up to 200 characters and the notes up to 1000. Description and notes are not part of the policy, so changing them does not create a new [version](#policy-version-history).

* `400 Bad Request` - The description or notes are too long.
* `404 Not Found` - There is no such policy.
* `409 Conflict` - The policy is a managed policy, which cannot be changed.

## Copy a Policy

| Method | Endpoint                          | Description                                          |
|--------|-----------------------------------|------------------------------------------------------|
| `POST` | `/api/policies/{policyName}/copy` | Create a new policy from an existing one.             |

Requires `policies:write`. The source is one of the caller's policies or, for a name beginning with `managed_`, a [managed policy](../../policies/sample_policies.md#managed-policies). The copy is the caller's own policy, active at once, with its own version history.

* `name` (required) - The name of the new policy, under the same rules as [saving a policy](#save-a-policy).

The copy has the source's policy and description. A copy of a managed policy has the note `Created from managed policy <name>`; a copy of the caller's own policy keeps its notes. Returns `201 Created` with the copy's details.

* `400 Bad Request` - The new name is missing or invalid.
* `404 Not Found` - There is no such policy to copy.
* `409 Conflict` - A policy with the new name already exists.

```
curl -X POST -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies/managed_common_pii/copy?name=my-pii"
```

## Policy Version History

Every time a policy's content changes, Philter automatically retains an immutable snapshot of it and advances the policy's revision. Saving a policy without changing its content — or changing only its description or notes — is not a new revision. The following endpoints expose that history and allow any prior revision to be restored.

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

Rollback restores the content of the specified revision as a **new** revision. History is never rewritten. The live policy's revision counter is incremented and the restored content is snapshotted. Every rollback is audited as `policy_rolled_back`.

#### Query Parameters

* `revision` (required) - The revision number to restore.
* `owner` (optional, admin only) - Username of another user whose policy to roll back.

#### Responses

* `201 Created` - Rollback succeeded. Body contains the new revision number.
* `400 Bad Request` - The `revision` parameter is missing or is not a number.
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

## Native JSON and concurrent changes

Upload and retrieval preserve native Phileas JSON field names, including `identifiers.dictionaries`; policy bodies are JSON objects, not JSON-encoded strings. Policy names are supplied in the `name` query parameter. Concurrent saves or rollback operations can return 409 if the governing revision changed. Read-only policy/history operations and compilation need `policies:read`; saves, deletion, and rollback need `policies:write`. History remains retained independently of deleting the live policy.
