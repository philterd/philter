# Consistent Pseudonymization (Replacement Scope)

When Philter replaces a piece of sensitive information with a generated value (for example, replacing a name with a realistic fake name), you often want the *same* original value to be replaced with the *same* generated value everywhere it appears — not just within a single document, but across every document you process. This is **consistent pseudonymization**, and it keeps a redacted dataset analytically useful: you can still tell that the same individual is referenced across many documents without ever knowing who they are.

Consistent pseudonymization is controlled by the **replacement scope** of a filter strategy.

## Setting the replacement scope

Each filter strategy has an optional `replacementScope` field. It accepts two values:

| Value | Meaning |
| ----- | ------- |
| `DOCUMENT` | **(Default.)** Each document is pseudonymized independently. The same value may receive a different replacement in a different document. |
| `CONTEXT` | The same value receives the same replacement across every document processed in the same [context](contexts.md). |

Set it on the strategy in your [policy](../policies/filter_strategies.md). For example, to anonymize SSNs consistently across a context:

```json
{
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        {
          "strategy": "RANDOM_REPLACE",
          "replacementScope": "CONTEXT"
        }
      ]
    }
  }
}
```

`CONTEXT` scope only takes effect when the redaction request supplies a [context](contexts.md); consistency is shared among documents redacted under that same context name.

## How the context mapping table works

Under `CONTEXT` scope, the first time a value is replaced Philter records the mapping and reuses it for every later occurrence of that value in the same context.

* **Persistence.** Mappings are stored in MongoDB and survive across requests and restarts, so consistency holds for documents processed days apart.
* **Scoping.** Each mapping is keyed by user **and** context name, so contexts are isolated from one another and one user's mappings are never visible to another.
* **Privacy.** The original value is **not** stored in clear text — only a SHA-256 hash of the token is kept, alongside the generated replacement, the filter type, a read counter, and a timestamp.
* **Capacity and eviction.** A context holds at most `MAX_CONTEXT_SIZE` mappings (default `10000`, configurable with the `MAX_CONTEXT_SIZE` environment variable; see [Settings](../settings.md)). When a context is full, the **least-read** mapping is evicted to make room. A value whose mapping has been evicted is treated as new the next time it is seen and receives a fresh replacement.

## Interaction with replacement strategies

Replacement scope matters only for strategies that generate a value that would otherwise vary from run to run. Deterministic strategies are already consistent across documents regardless of scope.

| Strategy | Effect of `CONTEXT` scope |
| -------- | ------------------------- |
| `RANDOM_REPLACE` (anonymize) | **Primary use.** The randomly generated surrogate is stored and reused, so the same value maps to the same surrogate across documents. |
| `FPE_ENCRYPT_REPLACE` | No table needed — format-preserving encryption is already deterministic for a given key, so the same input always encrypts to the same value. See [FPE](../policies/filter_strategies.md#the-fpe_encrypt_replace-filter-strategy). |
| `HASH_SHA256_REPLACE` | Deterministic on its own (unless salted), so it is consistent regardless of scope. |
| `STATIC_REPLACE`, `MASK`, `REDACT` | Produce a fixed output for a given input, so scope has no effect. |

## Managing the mappings (API)

The mapping table for a context can be inspected and managed through the context-entries API. All endpoints are scoped to the authenticated user.

| Method & path | Description |
| ------------- | ----------- |
| `GET /api/contexts/{name}/entries` | List the mappings in a context (paged with `offset`/`limit`). Each entry returns its id, replacement, filter type, read count, and timestamp. The original (hashed) token is never returned. |
| `DELETE /api/contexts/{name}/entries` | Remove all mappings from the context. |
| `DELETE /api/contexts/{name}/entries/{entryId}` | Remove a single mapping. |
| `GET /api/contexts/{name}/entries/export` | Export the context's mappings. |
| `POST /api/contexts/{name}/entries/import` | Import mappings into the context (subject to the same capacity/eviction limit). |

Deleting a mapping means the next occurrence of that value is treated as new and receives a fresh replacement.

## Not the same as disambiguation scope

Replacement scope is distinct from **[disambiguation scope](disambiguation.md)**, despite the similar `Document`/`Context` wording:

* **Replacement scope** (this page) is set **per filter strategy** in the policy and controls whether *generated replacements* are reused across documents.
* **Disambiguation scope** is set **on the context** and controls whether Philter shares *entity-type disambiguation* knowledge across documents to improve detection accuracy.

## See Also

* [Redaction Contexts](contexts.md)
* [Filter Strategies](../policies/filter_strategies.md)
* [Disambiguation](disambiguation.md)
