# Redaction Contexts

Redaction Contexts are a powerful organizational and functional feature of Philterd Data Services. They serve two primary purposes: logically grouping related document processing tasks and ensuring referential integrity when using replacement strategies like anonymization.

By utilizing contexts, you can manage your data protection activities more effectively, whether you are organizing by department, project, or individual client.

## How Contexts Work

The most critical technical feature of a context is its ability to maintain referential integrity during redaction.

When you redact a document within a specific context using a strategy like `ANONYMIZE`, the platform remembers the mapping between the original sensitive information and the replacement value it generated. If you subsequently process another document within the same context that contains the same sensitive information (e.g., the same patient name), Philterd will use the exact same replacement value.

This ensures that your redacted datasets remain analytically useful—you can still tell that the same individual is being referenced across multiple documents without ever knowing their actual identity.

### Co-referencing (coref)

When the `coref` option is enabled for a context, anonymized PII is co-referenced in the documents. This ensures that names remain consistent even when only parts of the name are used. For example, if "John Smith" is anonymized as "Daniel Jones", then subsequent references to "John" will be consistently anonymized as "Daniel". This feature helps maintain the narrative flow and clarity of redacted documents.

### Entity Type Disambiguation

Entity type disambiguation can be enabled or disabled for a context. When enabled, Philterd Data Services uses information from all documents in the context to resolve overlapping or conflicting entity types. This allows the system to make more informed decisions about the type of sensitive information identified.

Note that co-referencing may not catch all references to the same entity. For example, misspelled names or abbreviations may not be detected, or general ambiguities may result in false positive and false negative matches.

### Entity Type Disambiguation

Entity type disambiguation helps resolve ambiguity when a piece of text could represent multiple entity types. For example, "Washington" could be a person's name or a location. When enabled for a context, Philterd uses the surrounding context and machine learning models to determine the most likely entity type.

This feature is optional and can be enabled or disabled on a per-context basis. Enabling disambiguation can improve the accuracy of redaction in complex documents where entity types are frequently ambiguous.

## Managing Contexts via the Dashboard

The **Contexts** page provides a centralized interface for managing these organizational units.

### Viewing Your Context Inventory

The main table on the Contexts page lists all the contexts you have created. You can quickly see the name of each context and perform administrative actions.

### Creating a New Context

1.  **Initiate Creation**: Click the **New Context** button at the top of the table.
2.  **Assign a Name**: Enter a unique and descriptive **Context Name** (e.g., `Clinical-Trial-Alpha` or `HR-Records-2023`).
3.  **Enable Co-referencing (Optional)**: Check the **Enable coref** checkbox if you want to enable co-referencing for this context.
4.  **Enable Entity Type Disambiguation (Optional)**: Check the **Enable entity type disambiguation** checkbox to improve entity type accuracy across the context.
5.  **Finalize**: Click **Save**. This context is now available to be selected during document uploads or API calls.

### Editing a Context

You can enable or disable co-referencing and entity type disambiguation for an existing context at any time:

1.  Click the **Edit** (pencil) icon for the target context.
2.  Toggle the **Enable coref** or **Enable entity type disambiguation** checkboxes as desired.
3.  Click **Save**.

### Inspecting Context Entries

To verify how information is being mapped within a context:

1.  Click the **View Context** (eye) icon for a specific row.
2.  A preview dialog will open, displaying up to 20 recent mapping entries. Each entry shows the **Token Hash** (a secure representation of the original data) and its corresponding **Replacement**.
3.  Click **Close** to return to the main list.

### Clearing a Context

There may be times when you want to reset the mappings within a context without deleting the context itself (e.g., at the start of a new project phase):

1.  Click the **Clear** (refresh) icon for the target context.
2.  **Warning**: This action will permanently delete all existing sensitive-to-redacted mappings. Future redactions in this context will generate new, different replacement values.
3.  Confirm the action by clicking **Clear**.

### Deleting a Context

To permanently remove a context and all its associated mappings:

1.  Click the **Delete** (trash) icon.
2.  **Impact**: Deleting a context removes the organizational unit and its internal mappings. This will **not** affect documents that have already been redacted and downloaded.
3.  **Default Context**: Every account has a **default** context. The default context cannot be deleted.

## Integration and Best Practices

*   **Organization**: Use contexts to mirror your internal organizational structure or project list.
*   **Consistency**: Always use the same context for documents that belong to the same logical dataset to ensure referential integrity.
*   **Automation**: Context names can be passed as a parameter in [API requests](../developers/developer_quick_start.md), allowing for seamless integration into your automated workflows.

