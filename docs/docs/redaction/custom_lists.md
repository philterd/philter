# Managing Custom Lists

Custom Lists provide a highly efficient and centralized way to manage groups of specific terms that you want to always redact.

Instead of manually entering these terms into each individual policy, you can define them once in a Custom List and then simply reference that list by name. This approach ensures that when you need to add or remove a term, you only have to update it in one place to have the change reflected across all associated redaction workflows.

## The Role of Custom Lists in Redaction

Custom Lists are typically used in two primary scenarios within a [redaction policy](policy_syntax.md):

*   **Whitelisting (Ignore Lists)**: Ensuring that specific, non-sensitive terms that might be mistaken for PII (like a company name "John Deere") are never redacted.
*   **Blacklisting (Target Lists)**: Ensuring that specific sensitive terms (like "Project Phoenix") are always identified and redacted, even if they don't match standard PII patterns.

## Managing Custom Lists via the Dashboard

The Philterd Data Services dashboard offers a streamlined interface for the entire lifecycle of your custom lists.

### Viewing Your Lists

To see your current lists, navigate to the **Custom Lists** page from the left menu. 

*   **List Name**: The unique identifier you use to reference this list in policies.
*   **Description**: An optional short description of the list's purpose or contents.
*   **Item Count**: A quick glance at how many terms are currently stored in the list.

### Creating a New Custom List

To create a new custom list:

1.  **Open the Creation Dialog**: Click the **New Custom List** button located above the lists table.
2.  **Assign a Unique Name**: Enter a **List Name**. We recommend using clear, descriptive names (e.g., `Employee-Names-2024` or `Project-Codenames`). This name is what you will use in your [policy JSON](policy_syntax.md).
3.  **Add a Description (Optional)**: Enter an optional **Description** (maximum 250 characters) to provide context about the list's purpose or contents.
4.  **Define Your Terms**: In the **List Items** text area, enter your terms. **Important**: Place each term on its own individual line. Each custom list can contain a maximum of 100 items.
5.  **Save**: Click the **Save** button. Your list is now active and ready to be integrated into your policies.

### Editing a List

To edit an existing custom list:

1.  Find the list in the table and click its **Edit** button.
2.  Modify the optional description field if needed (maximum 250 characters).
3.  Add new terms on new lines or delete existing ones from the text area. Each list can contain a maximum of 100 items.
4.  Click **Save**. All policies referencing this list will immediately begin using the updated set of terms.

Please note a list's name cannot be changed once it has been created. If you need a new name, you should create a new list.

### Deleting a List

1.  Click the **Delete** (trash) icon for the list you wish to remove.
2.  **Review the Warning**: A confirmation dialog will appear. Exercise caution: if any of your active [redaction policies](policies.md) currently reference this list, those policies may fail or behave unexpectedly once the list is gone.
3.  **Confirm**: Click **Delete** to permanently remove the list from your account.

## Using Custom Lists in Policies

To use a custom list in a policy, you reference it by its name with the `list:` prefix. This can be done in the `termsToIgnore` (for whitelisting) and `termsToAlwaysRedact` (for blacklisting) sections of a [simplified policy](policy_syntax.md).

For example, if you have a custom list named `my-custom-list`, you would reference it as `list:my-custom-list` in your policy:

```json
{
  "name": "my-policy",
  "termsToIgnore": [
    "list:my-custom-list"
  ],
  "filters": {
    "PERSON": [
      {
        "strategy": "REDACT"
      }
    ]
  }
}
```

When the policy is processed, `list:my-custom-list` will be replaced with the actual terms contained within that list.

## Programmatic Management via API

For developers and organizations with dynamic data protection needs, Philterd Data Services provides a set of API endpoints for managing custom lists. This enables you to automate the synchronization of your internal "ignore" or "redact" lists with the Philterd platform, among other use-cases.

*   **List Retrieval**: Programmatically fetch the names of all your custom lists.
*   **Item Management**: Retrieve, add, or update the specific items within any list.
*   **Automated Lifecycle**: Create and delete lists as part of your automated CI/CD or data governance pipelines.

For detailed information on authenticating these API calls, please refer to the [API](../api/api_overview.md) documentation.

