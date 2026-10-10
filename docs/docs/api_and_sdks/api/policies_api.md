# Policies API

The Policies API provides endpoints for retrieving, uploading, and deleting [policies](../../policies/filter_policies.md).

> **Admin cross-user access:** by default each endpoint operates on the calling user's own policies. An **admin** may target another user by adding an `owner=<username>` query parameter to any endpoint (list, get, create, replace, delete). A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`. A deactivated user may be named as `owner`: deactivation keeps their data, and an admin reaches it as for an active user.

> Philter serves HTTPS on port 8080 with a generated self-signed certificate by default, so the `curl` examples on this page pass `-k`. See the [TLS](../../settings.md#tls) settings to supply your own certificate.


## Get Policy Names

| Method | Endpoint        | Description                    |
| ------ |-----------------|--------------------------------| 
| `GET` | `/api/policies` | Get the names of policies (paginated). |

Returns an object with the policies on the requested page in `policies` and `total`, how many there are across every page. See [Listings](../api.md#listings) for paging, sorting, and searching.

### Query Parameters

* `offset` (optional, default: `0`) - The number of policies to skip.
* `limit` (optional, default: `25`, max: `100`) - The maximum number of policies to return.
* `q` (optional) - Only policies whose name contains `q`, ignoring case.
* `sort` (optional, default: `name`) - `name`, `created`, or `updated`. The `managed` and `deleted` listings sort by `name` only.
* `order` (optional, default: `asc`) - `asc` or `desc`.
* `all_users` (optional, default: `false`) - List every user's policies instead of the caller's. Each item is then an object with the policy's `name` and its `owner`'s username. Managed policies are not included. Requires an administrator and `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (disabled by default), as `owner` does; otherwise it returns `404 Not Found`. Cannot be combined with `owner`.
* `managed` (optional, default: `false`) - List the built-in [managed policies](../../policies/sample_policies.md#managed-policies) instead of the caller's. Each item is then an object with the policy's `name` and `description`. Cannot be combined with `owner` or `all_users`.
* `deleted` (optional, default: `false`) - List the caller's deleted policies whose [version history](#policy-version-history) is kept, by name, instead of the live ones. Each item is then an object with the policy's `name`, its `latestRevision`, when it was deleted (`deletedAt`), and the username of who deleted it (`deletedBy`). `deletedAt` and `deletedBy` are `null` for a policy deleted before Philter recorded deletions. A policy created again under a deleted name is live, and is no longer listed. Can be combined with `owner`, but not with `all_users` or `managed`.

Example request:

```
curl -k -H "Authorization: Bearer <token>" "https://localhost:8080/api/policies?offset=0&limit=100"
```

Example response, the caller's policy names:

```json
{
  "policies": [
    "default",
    "my-policy"
  ],
  "total": 2
}
```

Example response with `all_users=true`:

```json
{
  "policies": [
    { "name": "default", "owner": "alice" },
    { "name": "default", "owner": "bob" }
  ],
  "total": 2
}
```

Example response with `deleted=true`:

```json
{
  "policies": [
    { "name": "claims-2025", "latestRevision": 4, "deletedAt": "2026-10-02T15:20:41.000Z", "deletedBy": "jordan" }
  ],
  "total": 1
}
```

Read a deleted policy's history with [List Versions](#list-versions) and [Fetch a Specific Revision](#fetch-a-specific-revision), by its name. To restore one, fetch the revision you want and save it under the name with [Save a Policy](#save-a-policy); the restored policy continues the same revision numbers. [Rollback](#rollback-to-a-prior-revision) only applies to a live policy.

Example response with `managed=true`:

```json
{
  "policies": [
    { "name": "managed_common_pii", "description": "Common PII including names, emails, phone numbers, and SSNs" },
    { "name": "managed_financial_pii", "description": "Financial PII including credit cards, bank routing numbers, and Bitcoin addresses" },
    { "name": "managed_healthcare_phi", "description": "Healthcare PHI including names, dates, ages, cities, states, zip codes, emails, phone numbers, and SSNs" }
  ],
  "total": 3
}
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

## Get a Policy Template

| Method | Endpoint                                 | Description                                  |
| ------ |------------------------------------------|----------------------------------------------|
| `GET`  | `/api/policies/templates/{templateName}` | Get a starting point for a new policy.       |

Returns a template: native policy JSON that Philter accepts for its running policy schema version, to edit and then save with [Save a Policy](#save-a-policy). Use it instead of keeping your own starting policy, which can fall behind the schema as filters are added. Requires `policies:read`.

The template named `default` is the one every new user's `default` policy is created from: it is configured to redact person names (detected by [PhEye](../../policies/policy_schema.md)), Social Security numbers, and email addresses. A template is not a policy, and cannot be used to redact until it is saved as one.

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/templates/default
```

Example response:

```json
{
  "identifiers": {
    "person": {
      "phEyeFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    },
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    },
    "emailAddress": {
      "emailAddressFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    }
  }
}
```

* `200 OK` - The template's policy JSON.
* `404 Not Found` - There is no template with that name. The message lists the templates there are.

## Save a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------| 
| `POST` | `/api/policies` | Create a policy. A name you already use is refused; to change an existing policy, [replace it](#replace-a-policy). |

### Query Parameters

* `name` (required) - The name of the policy to create.

Set the policy's description and notes afterwards with [`PUT /api/policies/{policyName}/details`](#set-a-policys-description-and-notes), which takes them in a JSON body. They are not accepted as query parameters here, since free text in a URL can exceed request header limits in some languages and is written to access logs; a request that sends them is refused.

### Validation

The policy is validated before it is stored. It must be valid JSON in the native Phileas policy format and contain a non-empty `identifiers` object describing the information to redact. A missing name or an invalid policy is rejected with `400 Bad Request` and a message describing the problem, and nothing is saved.

### Responses

* `201 Created` - The policy was created.
* `400 Bad Request` - The policy name is missing or invalid, the policy is invalid, or the request has a `description` or `notes` parameter. A name may be up to 50 characters of letters, digits, `_` and `-`, may not begin with `managed_`, and may not be `templates`, which is where [policy templates](#get-a-policy-template) are read.
* `404 Not Found` - The owner does not exist or may not be reached.
* `409 Conflict` - You already have a policy with this name, including one created by a concurrent request. Nothing is changed. The body carries a `message` and the `reason` `policy_exists`.

Example request:

```
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies?name=my-policy" -d @policy.json
```

Example `409` response:

```json
{
  "message": "A policy with this name already exists.",
  "reason": "policy_exists"
}
```

## Replace a Policy

| Method | Endpoint                     | Description                                                                       |
| ------ |------------------------------|-----------------------------------------------------------------------------------|
| `PUT` | `/api/policies/{policyName}` | Replace an existing policy with the request body, as a new revision. |

The policy is validated as when [creating one](#validation), and the replaced content is kept in the [version history](#policy-version-history).

The policy's description and notes are kept. Change them with [`PUT /api/policies/{policyName}/details`](#set-a-policys-description-and-notes).

### Query Parameters

* `owner` (optional, admin only) - Username of another user whose policy to replace.

### Responses

* `200 OK` - The policy was replaced.
* `400 Bad Request` - The policy is invalid, or the request has a `description` or `notes` parameter.
* `404 Not Found` - There is no such policy. The body carries a `message`, except when the `owner` does not exist or may not be reached.
* `409 Conflict` - The policy was not replaced, and nothing is changed. The body carries a `message` and a `reason`:
    * `policy_managed` - The policy is a [managed policy](../../policies/sample_policies.md#managed-policies), which cannot be replaced. Copy it and change the copy instead.
    * `policy_changed` - The policy changed after this request read it. Reload the policy and retry.

Example request:

```
curl -X PUT -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies/my-policy" -d @policy.json
```

## Delete a Policy

| Method   | Endpoint                     | Description                                                                                                                                 |
|----------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------| 
| `DELETE` | `/api/policies/{policyName}` | Delete a policy, where {policyName} is the name of the policy to delete. |

### Responses

* `200 OK` - The policy was deleted.
* `400 Bad Request` - The policy name is missing.
* `404 Not Found` - There is no such policy. The body carries a `message`, except when the `owner` does not exist or may not be reached.
* `409 Conflict` - The policy was not deleted, and it is kept. The body carries a `message` and a `reason`:
    * `policy_default` - The policy is the `default` policy.
    * `policy_managed` - The policy is a managed policy.

Example request:

```
curl -X DELETE -k -H "Authorization: Bearer <token>" https://localhost:8080/api/policies/my-policy
```

Example `409` response:

```json
{
  "message": "Cannot delete the default policy.",
  "reason": "policy_default"
}
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

A field left out is left as it is, and an empty value clears it. The description may be up to 200 characters and the notes up to 1000, in any language. Description and notes are not part of the policy, so changing them does not create a new [version](#policy-version-history).

* `400 Bad Request` - The description or notes are too long.
* `404 Not Found` - There is no such policy.
* `409 Conflict` - The policy is a managed policy, which cannot be changed. The body carries a `message` and the `reason` `policy_managed`.

## Copy a Policy

| Method | Endpoint                          | Description                                          |
|--------|-----------------------------------|------------------------------------------------------|
| `POST` | `/api/policies/{policyName}/copy` | Create a new policy from an existing one.             |

Requires `policies:write`. The source is one of the caller's policies or, for a name beginning with `managed_`, a [managed policy](../../policies/sample_policies.md#managed-policies). The copy is the caller's own policy, active at once, with its own version history.

* `name` (required) - The name of the new policy, under the same rules as [saving a policy](#save-a-policy).

The copy has the source's policy and description. A copy of a managed policy has the note `Created from managed policy <name>`; a copy of the caller's own policy keeps its notes. Returns `201 Created` with the copy's details.

* `400 Bad Request` - The new name is missing or invalid.
* `404 Not Found` - There is no such policy to copy.
* `409 Conflict` - A policy with the new name already exists. The body carries a `message` and the `reason` `policy_exists`, as when [creating a policy](#save-a-policy).

```
curl -X POST -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies/managed_common_pii/copy?name=my-pii"
```

## Policy Version History

Every time a policy's content changes, Philter automatically retains an immutable snapshot of it and advances the policy's revision. Saving a policy without changing its content, or changing only its description or notes, is not a new revision. The following endpoints expose that history and allow any prior revision to be restored.

### List Versions

| Method | Endpoint                                  | Description                                          |
|--------|-------------------------------------------|------------------------------------------------------|
| `GET`  | `/api/policies/{policyName}/versions`     | List retained revisions, most recent first.          |

#### Query Parameters

* `offset` (optional, default: `0`) - Number of entries to skip.
* `limit` (optional, default: `25`, max: `100`) - Maximum number of entries to return.
* `owner` (optional, admin only) - Username of another user whose policy to browse.
* `order` (optional, default: `desc`) - `desc` lists the most recent revision first, `asc` the oldest. See [Listings](../api.md#listings).

#### Response

Returns the version summaries on the requested page in `versions`, and `total`, how many versions the policy has retained. The full policy JSON is **not** included; use [Fetch a Specific Revision](#fetch-a-specific-revision) to retrieve the full content of a particular revision.

```json
{
  "versions": [
    { "revision": 3, "capturedTimestamp": "2026-06-09T14:23:00.000Z", "contentHash": "a1b2c3...", "author": "admin" },
    { "revision": 2, "capturedTimestamp": "2026-06-08T09:11:00.000Z", "contentHash": "d4e5f6...", "author": "jordan" },
    { "revision": 1, "capturedTimestamp": "2026-06-07T16:04:00.000Z", "contentHash": "g7h8i9...", "author": "jordan" }
  ],
  "total": 3
}
```

* `author` - The username of the user whose change produced the revision: the caller who created, replaced, or rolled back the policy, which may be an administrator acting for its owner. It is `null` when that is not known: for a revision made before Philter recorded authors, for a managed policy, or for a revision first captured when the policy was used to redact.

A deleted policy's history is kept and listed the same way. List deleted policies with [`GET /api/policies?deleted=true`](#get-policy-names).

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

Each `replace` and `remove` also carries `oldValue`, the value at that path before the change, so the change can be shown side by side without fetching both revisions. RFC 6902 has a client that applies the patch ignore members it does not define, so the changes still apply as a standard patch. An `add` has no `oldValue`, since the path had no value before. A value that is JSON `null` is written as `null`, not left out.

```json
{
  "from": 1,
  "to": 3,
  "changes": [
    { "op": "replace", "path": "/identifiers/ssn/ssnFilterStrategies", "value": [{ "strategy": "MASK" }], "oldValue": [{ "strategy": "REDACT" }] },
    { "op": "remove",  "path": "/identifiers/phoneNumber", "oldValue": { "phoneNumberFilterStrategies": [{ "strategy": "REDACT" }] } },
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
* `400 Bad Request` - The `revision` parameter is missing or is not a number. The body carries a `message`.
* `404 Not Found` - The policy or the target revision does not exist. The body's `message` says which, for example `Revision 99 does not exist.`, except when the `owner` does not exist or may not be reached.
* `409 Conflict` - The policy was not rolled back. The body carries a `message` and a `reason`:
    * `policy_managed` - Managed policies cannot be rolled back.
    * `policy_changed` - The policy changed after this request read it. Reload it and retry.

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

Upload and retrieval preserve native Phileas JSON field names, including `identifiers.dictionaries`; policy bodies are JSON objects, not JSON-encoded strings. Create (`POST /api/policies`) takes the new policy's name in the `name` query parameter, and copy takes the copy's name there; every other operation names the policy in the path. Concurrent replace or rollback operations can return 409 with the reason `policy_changed` if the governing revision changed. Read-only policy/history operations and compilation need `policies:read`; saves, deletion, and rollback need `policies:write`. History remains retained independently of deleting the live policy.
