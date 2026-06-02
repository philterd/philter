# Redaction Contexts

Redaction Contexts are a powerful organizational and functional feature of Philter. They serve two primary purposes: logically grouping related document processing tasks and ensuring referential integrity when using replacement strategies like anonymization.

By utilizing contexts, you can manage your data protection activities more effectively, whether you are organizing by department, project, or individual client.

## How Contexts Work

The most critical technical feature of a context is its ability to maintain referential integrity during redaction.

When you redact a document within a specific context using a strategy like `ANONYMIZE`, the platform remembers the mapping between the original sensitive information and the replacement value it generated. If you subsequently process another document within the same context that contains the same sensitive information (e.g., the same patient name), Philterd will use the exact same replacement value.

This ensures that your redacted datasets remain analytically useful—you can still tell that the same individual is being referenced across multiple documents without ever knowing their actual identity.

### Entity Type Disambiguation

Entity type disambiguation helps resolve ambiguity when the identical piece of text is identified as more than one entity type. For example, a nine-digit number could be claimed by both the SSN filter and a custom identifier filter. When enabled for a context, Philter compares the words surrounding the text against what it has learned for each candidate type in that context and keeps the most likely one. This uses a vector-based comparison of the surrounding words (not a machine learning model), and it improves as more text is processed in the same context. See [Span Disambiguation](../other_features/span_disambiguation.md) for details.

This feature is optional and can be enabled or disabled on a per-context basis. Enabling disambiguation can improve the accuracy of redaction in complex documents where entity types are frequently ambiguous.

## Managing Contexts via the Dashboard

The **Contexts** page provides a centralized interface for managing these organizational units.

### Viewing Your Context Inventory

The main table on the Contexts page lists all the contexts you have created. You can quickly see the name of each context and perform administrative actions.

### Creating a New Context

1.  **Initiate Creation**: Click the **New Context** button at the top of the table.
2.  **Assign a Name**: Enter a descriptive **Context Name** (e.g., `Clinical-Trial-Alpha` or `HR-Records-2023`). Context names are **globally unique**—if another user has already created a context with the same name, creation is rejected.
3.  **Enable Entity Type Disambiguation (Optional)**: Check the **Enable entity type disambiguation** checkbox to improve entity type accuracy across the context.
4.  **Enable the Redaction Ledger (Optional)**: Check the **Enable the redaction ledger** checkbox to record a [redaction ledger](ledgers.md) for redactions performed in this context. This option is unchecked by default.
5.  **Finalize**: Click **Save**. This context is now available to be selected during document uploads or API calls.

### Editing a Context

You can enable or disable entity type disambiguation and the redaction ledger for an existing context at any time:

1.  Click the **Edit** (pencil) icon for the target context.
2.  Toggle the **Enable entity type disambiguation** or **Enable the redaction ledger** checkboxes as desired.
3.  Click **Save**.

### Inspecting Context Entries

To verify how information is being mapped within a context:

1.  Click the **View Context** (eye) icon for a specific row.
2.  A preview dialog will open, displaying up to 20 recent mapping entries. Each entry shows the **Token Hash** (a secure representation of the original data) and its corresponding **Replacement**.
3.  Click **Close** to return to the main list.

### Clearing a Context

There may be times when you want to reset the mappings within a context without deleting the context itself (e.g., at the start of a new project phase):

1.  Click the **Clear** (refresh) icon for the target context.
2.  **Warning**: This action will permanently delete all existing sensitive-to-redacted mappings and the context's learned disambiguation vectors. Future redactions in this context will generate new, different replacement values.
3.  Confirm the action by clicking **Clear**.

### Deleting a Context

To permanently remove a context and all its associated mappings:

1.  Click the **Delete** (trash) icon.
2.  **Impact**: Deleting a context removes the organizational unit, its internal mappings, and its learned disambiguation vectors. This will **not** affect documents that have already been redacted and downloaded.
3.  **Permissions**: A context can be deleted only by the user that created it or by an admin.

> Contexts are shared and are **not** deleted when a user is removed; their mappings and disambiguation vectors are retained.

## Capacity and Eviction

Each context is bounded so that referential-integrity storage does not grow without limit:

*   **Token mappings** — Each context stores up to `MAX_CONTEXT_SIZE` entries (default `10000`, overridable via the `MAX_CONTEXT_SIZE` environment variable). When the limit is reached, the **least-read** entry is evicted before the new one is inserted (ties broken by oldest entry first). Read counts are updated on every lookup, including cache hits.
*   **Disambiguation vectors** — When entity-type disambiguation is enabled, each `(user, context)` pair stores up to `MAX_VECTORS_PER_CONTEXT` vectors (default `100000`). Eviction here is FIFO by insertion order.

In practice this means a long-running context will retain its most actively-referenced mappings indefinitely while quietly discarding entries that no incoming document has touched in a long time.

## Deleting a Context With Pending Work

If your application uses [asynchronous PDF redaction](../api_and_sdks/api/documents_api.md), Philter blocks deletion of any context that has a document in the `PENDING` or `PROCESSING` state. The API will return `409 Conflict`. Wait for the jobs to finish (or delete them from the Documents API) before deleting the context.

## Exporting and Importing Mappings

A context's mapping table can be exported and imported through the [Contexts API](../api_and_sdks/api/contexts_api.md#export-a-contexts-mapping-table). This lets you reuse the same replacements across separate environments or rebuild a context's mappings after it has been cleared:

*   **Export** returns the context's mappings as a portable JSON document. Only the SHA-256 hash of each original value is exported (never the original value itself), so the same value continues to map to the same replacement wherever the table is imported.
*   **Import** loads such a document into an existing context. By default an incoming value that already exists is skipped; you can choose to overwrite instead.

Export and import are restricted to the user that **created** the context or to an **admin**. An admin may export or import any context.

## Integration and Best Practices

*   **Organization**: Use contexts to mirror your internal organizational structure or project list.
*   **Consistency**: Always use the same context for documents that belong to the same logical dataset to ensure referential integrity.
*   **Portability**: Use the [export and import endpoints](../api_and_sdks/api/contexts_api.md#export-a-contexts-mapping-table) to carry consistent replacements between environments (for example, from staging to production).
*   **Automation**: Context names can be passed as a parameter in [API requests](../developers/developer_quick_start.md), allowing for seamless integration into your automated workflows.

