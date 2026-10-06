# Managing Custom Lists

Custom Lists provide a highly efficient and centralized way to manage groups of specific terms that you want to always redact.

Instead of manually entering these terms into each individual policy, you can define them once in a Custom List and then simply reference that list by name. This approach ensures that when you need to add or remove a term, you only have to update it in one place to have the change reflected across all associated redaction workflows.

## The Role of Custom Lists in Redaction

Custom Lists are typically used in two primary scenarios within a [redaction policy](policies.md):

*   **Whitelisting (Ignore Lists)**: Ensuring that specific, non-sensitive terms that might be mistaken for PII (like a company name "John Deere") are never redacted.
*   **Blacklisting (Target Lists)**: Ensuring that specific sensitive terms (like "Project Phoenix") are always identified and redacted, even if they don't match standard PII patterns.

## Managing Custom Lists

Custom lists are managed with the [Custom Lists API](../api_and_sdks/api/custom_lists_api.md).

| Task | Request |
| --- | --- |
| List your lists | [`GET /api/lists`](../api_and_sdks/api/custom_lists_api.md#get-list-names) |
| Read a list | [`GET /api/lists/{name}`](../api_and_sdks/api/custom_lists_api.md#get-a-list) |
| Create a list | [`POST /api/lists/{name}`](../api_and_sdks/api/custom_lists_api.md#create-a-list), with the terms as a JSON array and an optional `description` |
| Replace a list | [`PUT /api/lists/{name}`](../api_and_sdks/api/custom_lists_api.md#replace-a-list), with the complete new terms as a JSON array |
| Delete a list | [`DELETE /api/lists/{name}`](../api_and_sdks/api/custom_lists_api.md#delete-a-list) |

* **Name**: the identifier you use to reference the list in your [policy JSON](../policies/policy_schema.md). Use clear, descriptive names (for example, `Employee-Names-2024` or `Project-Codenames`). A list cannot be renamed; create a new list instead.
* **Items**: each list can contain a maximum of 100 items, each up to 50 characters. There is no limit on the number of lists. Replacing a list replaces its items, and every policy that references it uses the new set immediately.
* **Deleting**: if any of your [redaction policies](policies.md) reference the list, those policies may fail or behave unexpectedly once it is gone.

## Using Custom Lists in Policies

To use a custom list in a policy, you reference it by its name with the `list:` prefix. This can be done in a top-level `ignored` list's `terms` (for whitelisting) or in a custom dictionary's `terms` (for blacklisting).

For example, if you have a custom list named `my-custom-list`, you would reference it as `list:my-custom-list` in your policy:

```json
{
  "name": "my-policy",
  "ignored": [
    {
      "name": "my-ignored-terms",
      "terms": [ "list:my-custom-list" ]
    }
  ],
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    }
  }
}
```

When the policy is processed, `list:my-custom-list` will be replaced with the actual terms contained within that list.

Every `list:` reference must name an existing list owned by the user whose policy is being applied.
A missing, deleted, or inaccessible list stops redaction before any output is produced. Synchronous
filter requests return `400 Bad Request` identifying the unavailable list names; asynchronous jobs
are marked failed with that reason. This applies to both dictionary terms and ignored terms.
An existing list with no items resolves to no terms; it is distinct from a missing list.

References are checked when redaction runs, including for pinned policy versions. Before deleting
a list used by a policy, update the policy's references or expect subsequent redaction to fail.

## Programmatic Management via API

For developers and organizations with dynamic data protection needs, Philter provides a set of API endpoints for managing custom lists. This enables you to automate the synchronization of your internal "ignore" or "redact" lists with the Philterd platform, among other use-cases.

*   **List Retrieval**: Programmatically fetch your custom lists, each with its name, description, and number of items.
*   **Item Management**: Retrieve the items within any list, or replace them.
*   **Automated Lifecycle**: Create and delete lists as part of your automated CI/CD or data governance pipelines.

For detailed information on authenticating these API calls, please refer to the [API](../api_and_sdks/api.md) documentation.

