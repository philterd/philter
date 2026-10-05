# Redaction Policies

Redaction Policies are the foundational rule sets that govern how Philter identifies, classifies, and processes sensitive information within your documents. A policy acts as a comprehensive blueprint, detailing exactly which types of Personally Identifiable Information (PII) and Protected Health Information (PHI) should be targeted and what specific actions should be taken when they are found.

With the [Policies API](../api_and_sdks/api/policies_api.md), you can create, refine, and maintain a library of policies tailored to different regulatory requirements, departments, or document types.

## Understanding Redaction Policy Syntax

Every policy in Philter is internally represented as a JSON (JavaScript Object Notation) object. This structured format allows for an incredible degree of granularity and flexibility. You can define specific "filters" for different data types (like social security numbers, names, or dates) and assign unique "strategies" to each (such as masking, encrypting, or replacing with a placeholder).

For a comprehensive guide on the available filters, processing strategies, and advanced configuration options, please refer to our detailed [Policy Schema Reference](../policies/policy_schema.md) documentation. You can also learn how Philterd resolves overlapping sensitive terms in our [Disambiguation](disambiguation.md) guide.

Policies can also be authored in [PhiSQL](../policies/phisql.md), a declarative language that compiles to this same JSON. A policy written as `REDACT SSN WITH MASK;` is easier to review than its JSON equivalent, which matters when a compliance reviewer rather than an engineer signs off on the rules.

## Managing Your Policies

Policies are managed with the [Policies API](../api_and_sdks/api/policies_api.md). To build a policy without writing JSON by hand, use the policy editor at [policies.philterd.ai](https://policies.philterd.ai) and save the result through the API.

| Task | Request |
| --- | --- |
| List your policies | [`GET /api/policies`](../api_and_sdks/api/policies_api.md#get-policy-names) |
| List the [managed policies](../policies/sample_policies.md#managed-policies) | [`GET /api/policies?managed=true`](../api_and_sdks/api/policies_api.md#get-policy-names) |
| Read a policy | [`GET /api/policies/{name}`](../api_and_sdks/api/policies_api.md#get-a-policy) |
| Create or update a policy | [`POST /api/policies?name=...`](../api_and_sdks/api/policies_api.md#save-a-policy), with the policy JSON as the body and optional `description` and `notes` |
| Read or set its description and notes | [`GET` and `PUT /api/policies/{name}/details`](../api_and_sdks/api/policies_api.md#get-a-policys-details) |
| Copy a policy | [`POST /api/policies/{name}/copy`](../api_and_sdks/api/policies_api.md#copy-a-policy) |
| Delete a policy | [`DELETE /api/policies/{name}`](../api_and_sdks/api/policies_api.md#delete-a-policy) |

Each account has a **default** policy that is used if no other policy is specified. The default policy cannot be deleted.

## Best Practices for Policy Management

*   **Descriptive Naming**: Use clear, descriptive names for your policies to easily identify their purpose.
*   **Descriptions and Notes**: Use the optional description and notes fields to provide additional information about your policies for future reference.
*   **Incremental Testing**: When creating complex policies, test them on sample documents to ensure they are performing as expected before applying them to large datasets.
*   **Version via Copying**: Before making major changes to a critical policy, consider copying it first to maintain a "known good" backup. Every saved change is also kept in the [version history](#policy-version-history).

## Policy Version History

Every time a policy is created or updated, Philter automatically retains an immutable snapshot of its content. These snapshots power the version history, diff, and rollback features described below.

### Listing retained revisions

```bash
curl -s https://philter:8080/api/policies/my-policy/versions \
  -H "Authorization: YOUR_API_KEY"
```

Returns a summary list ordered by revision descending (most recent first):

```json
[
  { "revision": 3, "capturedTimestamp": "2026-06-09T14:23:00Z", "contentHash": "a1b2..." },
  { "revision": 2, "capturedTimestamp": "2026-06-08T09:11:00Z", "contentHash": "c3d4..." },
  { "revision": 1, "capturedTimestamp": "2026-06-07T16:04:00Z", "contentHash": "e5f6..." }
]
```

Supports `?offset=` and `?limit=` for pagination.

### Fetching a specific revision

```bash
curl -s https://philter:8080/api/policies/my-policy/versions/2 \
  -H "Authorization: YOUR_API_KEY"
```

Returns the full policy JSON as it existed at revision 2, using the same format as `GET /api/policies/{policyName}`.

### Diffing two revisions

```bash
# Diff the two most recent revisions (omit from/to to use this default)
curl -s "https://philter:8080/api/policies/my-policy/diff" \
  -H "Authorization: YOUR_API_KEY"

# Diff specific revisions
curl -s "https://philter:8080/api/policies/my-policy/diff?from=1&to=3" \
  -H "Authorization: YOUR_API_KEY"
```

Returns an envelope with the compared revision numbers and an RFC 6902 JSON Patch array describing what changed. Object-level changes (adds, removes, replaces) are reported per field path:

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

If the two revisions have identical content, `changes` is an empty array.

### Rolling back to a prior revision

Rollback restores the content of a prior revision as a **new** revision. History is never rewritten. The live policy is updated with the prior content and its revision counter is incremented. Every rollback is audited.

```bash
curl -s -X POST "https://philter:8080/api/policies/my-policy/rollback?revision=1" \
  -H "Authorization: YOUR_API_KEY"
```

Returns `201 Created` with the new revision number:

```json
{ "revision": 4 }
```

Managed policies cannot be rolled back (returns `409 Conflict`). If the target revision does not exist, returns `404 Not Found`.

### HTTP status codes

| Code | Meaning |
|---|---|
| `200 OK` | Request succeeded (list, fetch, diff). |
| `201 Created` | Rollback succeeded. Body contains the new revision number. |
| `400 Bad Request` | The policy name is missing, fewer than two revisions exist for a default diff, or only one of `from`/`to` was supplied. |
| `401 Unauthorized` | The `Authorization` header is absent or the API key is not recognized. |
| `404 Not Found` | The policy or the requested revision does not exist, or a non-admin caller named another user as `owner`. |
| `409 Conflict` | Rollback attempted on a managed policy. |

### Admin cross-user access

All version history endpoints support the `?owner=` parameter. Admins may browse and roll back another user's policies by supplying that user's email:

```bash
curl -s "https://philter:8080/api/policies/my-policy/versions?owner=other@example.com" \
  -H "Authorization: ADMIN_API_KEY"
```

