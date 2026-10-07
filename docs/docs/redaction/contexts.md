# Redaction Contexts

Redaction Contexts are a powerful organizational and functional feature of Philter. They serve two primary purposes: logically grouping related document processing tasks and ensuring referential integrity when using replacement strategies like pseudonymization.

By utilizing contexts, you can manage your data protection activities more effectively, whether you are organizing by department, project, or individual client.

## How Contexts Work

The most critical technical feature of a context is its ability to maintain referential integrity during redaction.

When you redact a document within a specific context using a pseudonymizing strategy like `RANDOM_REPLACE` configured with `CONTEXT` replacement scope, the platform remembers the mapping between the original sensitive information and the replacement value it generated. If you subsequently process another document within the same context that contains the same sensitive information (e.g., the same patient name), Philter will use the exact same replacement value.

This ensures that your redacted datasets remain analytically useful. You can still tell that the same individual is being referenced across multiple documents without ever knowing their actual identity.

Enabling this is covered in detail, along with the strategy interactions and the mapping-table behavior, under [Consistent Pseudonymization](replacement_scope.md).

### Entity Type Disambiguation

Entity type disambiguation helps resolve ambiguity when the identical piece of text is identified as more than one entity type. For example, a nine-digit number could be claimed by both the SSN filter and a custom identifier filter. When enabled for a context, Philter compares the words surrounding the text against what it has learned for each candidate type in that context and keeps the most likely one. This uses a vector-based comparison of the surrounding words (not a machine learning model), and it improves as more text is processed in the same context. See [Span Disambiguation](../other_features/span_disambiguation.md) for details.

This feature is optional and can be enabled or disabled on a per-context basis. Enabling disambiguation can improve the accuracy of redaction in complex documents where entity types are frequently ambiguous.

## Managing Contexts

Contexts are managed with the [Contexts API](../api_and_sdks/api/contexts_api.md). Every new user starts with a context named `default`, created automatically; you can use it, change its settings, or delete it like any other.

| Task | Request |
| --- | --- |
| List your contexts | [`GET /api/contexts`](../api_and_sdks/api/contexts_api.md#get-context-names) |
| Create a context | [`POST /api/contexts?name=...`](../api_and_sdks/api/contexts_api.md#create-a-context), with optional `entity_type_disambiguation` and `ledger` flags |
| Change its settings | [`PUT /api/contexts/{name}`](../api_and_sdks/api/contexts_api.md#update-a-context) |
| Show counts by filter type | [`GET /api/contexts/{name}`](../api_and_sdks/api/contexts_api.md#get-context-details) |
| List individual mappings | [`GET /api/contexts/{name}/entries`](../api_and_sdks/api/contexts_api.md#list-context-entries) |
| Clear its mappings | [`DELETE /api/contexts/{name}/entries`](../api_and_sdks/api/contexts_api.md#empty-a-context) |
| Delete it | [`DELETE /api/contexts/{name}`](../api_and_sdks/api/contexts_api.md#delete-a-context) |

Context names are **unique per user**. You cannot have two contexts with the same name, but a name you use does not prevent another user from using the same name. A name cannot contain `/`, `\`, `;`, `%`, or control characters, and cannot be `.` or `..`, since it is used in request paths.

Each user can have **at most 10 contexts**, including `default`. The limit is per user: other users' contexts do not count toward it. A request for an eleventh is refused with `409 Conflict` and `reason` set to `context_limit_reached`; delete a context to make room. The limit is fixed and cannot be changed with a setting. The [redaction ledger](ledgers.md) is off for a new context unless `ledger=true` is set.

Listing entries returns replacement metadata; exports include keyed token hashes and require the same deployment encryption key when imported elsewhere.

### Clearing a Context

Clearing a context resets its mappings without deleting the context itself (for example, at the start of a new project phase). It permanently deletes all existing sensitive-to-redacted mappings and the context's learned disambiguation vectors. Future redactions in this context generate new, different replacement values.

### Deleting a Context

Deleting a context removes the context, its internal mappings, and its learned disambiguation vectors. It does **not** affect documents that have already been redacted and downloaded. A context can be deleted only by the user that created it or by an admin.

> Contexts are owned by the user that created them. Users are deactivated rather than deleted, and a deactivated user's contexts, mappings, and disambiguation vectors are kept, so they are there again if the user is reactivated.

## Capacity and Eviction

Each context is bounded so that referential-integrity storage does not grow without limit:

*   **Token mappings**. Each context stores up to `MAX_CONTEXT_SIZE` entries (default `10000`, overridable via the `MAX_CONTEXT_SIZE` environment variable). When the limit is reached, the **least-read** entry is evicted before the new one is inserted (ties broken by oldest entry first). Read counts are updated on every lookup, including cache hits.
*   **Disambiguation vectors**. When entity-type disambiguation is enabled, each `(user, context)` pair stores up to `MAX_VECTORS_PER_CONTEXT` vectors (default `100000`). Eviction here is FIFO by insertion order.

In practice this means a long-running context will retain its most actively-referenced mappings indefinitely while quietly discarding entries that no incoming document has touched in a long time.

## Deleting a Context With Pending Work

If your application uses [asynchronous PDF redaction](../api_and_sdks/api/documents_api.md), Philter blocks deletion of any context that has a document in the `PENDING` or `PROCESSING` state. The API will return `409 Conflict`. Wait for the jobs to finish (or delete them from the Documents API) before deleting the context.

## Exporting and Importing Mappings

A context's mapping table can be exported and imported through the [Contexts API](../api_and_sdks/api/contexts_api.md#export-a-contexts-mapping-table). This lets you reuse the same replacements across separate environments or rebuild a context's mappings after it has been cleared:

*   **Export** returns the context's mappings as a JSON document. Only a keyed hash of each original value is exported, never the original value itself, so the same value continues to map to the same replacement wherever the table is imported. The key is derived from `PHILTER_ENCRYPTION_KEY`, so an export can be imported elsewhere in the same deployment but not into a different one.
*   **Import** loads such a document into an existing context. By default an incoming value that already exists is skipped; you can choose to overwrite instead.

Export and import are restricted to the user that **created** the context or to an **admin** with `ADMIN_CROSS_USER_ACCESS_ENABLED=true`. Because context names are unique only per user, an admin reaching another user's context supplies that user's username in the `owner` query parameter to identify it unambiguously; without `owner`, the operation applies to the caller's own context of that name.

## Integration and Best Practices

*   **Organization**: Use contexts to mirror your internal organizational structure or project list.
*   **Consistency**: Always use the same context for documents that belong to the same logical dataset to ensure referential integrity.
*   **Portability**: Use the [export and import endpoints](../api_and_sdks/api/contexts_api.md#export-a-contexts-mapping-table) to carry consistent replacements between environments (for example, from staging to production).
*   **Automation**: Context names can be passed as a parameter in [API requests](../developers/developer_quick_start.md), allowing for seamless integration into your automated workflows.

