# Redaction Policies

Redaction Policies are the foundational rule sets that govern how Philter identifies, classifies, and processes sensitive information within your documents. A policy acts as a comprehensive blueprint, detailing exactly which types of Personally Identifiable Information (PII) and Protected Health Information (PHI) should be targeted and what specific actions should be taken when they are found.

Through the Policies management view in your dashboard, you can create, refine, and maintain a library of policies tailored to different regulatory requirements, departments, or document types.

## Understanding Redaction Policy Syntax

Every policy in Philter is internally represented as a JSON (JavaScript Object Notation) object. This structured format allows for an incredible degree of granularity and flexibility. You can define specific "filters" for different data types (like social security numbers, names, or dates) and assign unique "strategies" to each (such as masking, encrypting, or replacing with a placeholder).

For a comprehensive guide on the available filters, processing strategies, and advanced configuration options, please refer to our detailed [Policy Schema Reference](../policies/policy_schema.md) documentation. You can also learn how Philterd resolves overlapping sensitive terms in our [Disambiguation](disambiguation.md) guide.

## Managing Your Policies

The Philterd dashboard provides a powerful interface for managing the entire lifecycle of your redaction policies.

### Creating a New Policy

To establish a new set of redaction rules:

1.  Click the **New Policy** button located at the top of the Policies page.
2.  Assign a unique and descriptive **Policy Name** (e.g., "HIPAA-Standard-Redaction" or "GDPR-PII-Masking").
3.  Use the intuitive configuration tabs to build your policy:
    *   **PII/PHI Filters**: This is where you enable or disable the identification of specific sensitive data types. You can toggle filters for names, addresses, financial identifiers, health-related codes, and much more.
    *   **Terms to Always Redact**: Use this tab to define a custom "blacklist." Any specific words or phrases entered here will be unconditionally redacted whenever they are encountered in a document.
    *   **Terms to Never Redact (Ignore)**: Conversely, use this tab to create a "whitelist." Any terms entered here will be protected from redaction (effectively "never redact"), even if they would otherwise be identified by one of the active filters.
    *   **General Options**: Configure high-level settings for the policy, such as how the output document should be formatted.
4.  Click the **Save** button to store your new policy and make it available for use in redaction tasks.

### Editing Existing Policies

As your compliance needs evolve, you may need to update your policies. Philterd offers two distinct ways to modify an existing policy:
*   **The Standard Editor**: Clicking the **Edit** (pencil) icon opens the user-friendly tabbed interface. This is the recommended method for most users, as it provides a structured way to adjust settings without worrying about JSON syntax.
*   **The Advanced JSON Editor**: For power users and developers, the **Advanced Editor** allows for direct modification of the raw JSON configuration. This provides the ultimate level of control, enabling you to implement complex logic and configurations not yet available in the standard UI.

### Duplicating a Policy

Often, you may need a new policy that is very similar to an existing one. Instead of starting from scratch, you can:

1.  Click the **Duplicate** (copy) icon next to the source policy.
2.  Provide a new, unique name for the copy.
3.  Click **Duplicate** to create the new policy, which you can then edit independently.

### Deleting a Policy

To maintain a clean environment, you can remove policies that are no longer in use:

1.  Click the **Delete** (trash) icon associated with the policy.
2.  Confirm the action in the security dialog.
3.  **Important Note**: Each account has a **default** policy that is used if no other policy is specified. The default policy cannot be deleted.

## Best Practices for Policy Management

*   **Descriptive Naming**: Use clear, descriptive names for your policies to easily identify their purpose.
*   **Descriptions and Notes**: Use the optional description and notes fields to provide additional information about your policies for future reference.
*   **Incremental Testing**: When creating complex policies, test them on sample documents to ensure they are performing as expected before applying them to large datasets.
*   **Version via Duplication**: Before making major changes to a critical policy, consider duplicating it first to maintain a "known good" backup.

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

Rollback restores the content of a prior revision as a **new** revision — history is never rewritten. The live policy is updated with the prior content and its revision counter is incremented. Every rollback is audited.

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

