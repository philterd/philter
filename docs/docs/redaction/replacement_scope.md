# Consistent Pseudonymization (Replacement Scope)

When Philter replaces a piece of sensitive information with a generated value (for example, replacing a name with a realistic fake name), you often want the *same* original value to be replaced with the *same* generated value everywhere it appears, not just within a single document but across every document you process. This is **consistent pseudonymization**, and it keeps a redacted dataset analytically useful: you can still tell that the same individual is referenced across many documents without ever knowing who they are.

Consistent pseudonymization is controlled by the **replacement scope** of a filter strategy.

## Setting the replacement scope

Each filter strategy has an optional `replacementScope` field. It accepts two values:

| Value | Meaning |
| ----- | ------- |
| `DOCUMENT` | **(Default.)** Every occurrence of a value in one document gets the same replacement. Each document gets its own replacements, even when documents are redacted in the same context. |
| `CONTEXT` | The same value receives the same replacement across every document processed in the same [context](contexts.md). |

Set it on the strategy in your [policy](../policies/filter_strategies.md). For example, to pseudonymize SSNs consistently across a context:

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

`CONTEXT` scope only takes effect when the redaction request supplies a [context](contexts.md); consistency is shared among documents redacted under that same context name. Without a context, a `CONTEXT`-scoped strategy keeps nothing, so every occurrence of a value gets its own replacement and every date its own random shift, even within one document.

## What counts as one document

Under `DOCUMENT` scope, one redaction request body is one document, and everything in it shares one set of replacements:

* **Plain text:** the whole body.
* **PDF:** every page.
* **Word, Excel, and RTF:** every part of the document.
* **Email (`.eml` and `.msg`):** the message body and all of its attachments.

Values are matched exactly, so `John Smith` and `J. Smith` get different replacements.

`DOCUMENT`-scoped replacements are kept only for the request. They are not stored in the context's mapping table, do not appear in `GET /api/contexts/{name}/entries`, and do not count toward `MAX_CONTEXT_SIZE`.

## How the context mapping table works

Under `CONTEXT` scope, the first time a value is replaced Philter records the mapping and reuses it for every later occurrence of that value in the same context.

* **Persistence.** Mappings are stored in Philter's database and survive across requests and restarts, so consistency holds for documents processed days apart.
* **Scoping.** Each mapping is keyed by user **and** context name, so contexts are isolated from one another and one user's mappings are never visible to another.
* **Privacy.** The original value is **not** stored in clear text. Only a keyed hash (HMAC-SHA256 under a key derived from `PHILTER_ENCRYPTION_KEY` and the context owner's username) is kept, alongside the generated replacement, the filter type, a read counter, and a timestamp. The key matters: most detected values are low-entropy, so a bare digest of one could be reversed by trying candidates.
* **Capacity and eviction.** A context holds at most `MAX_CONTEXT_SIZE` mappings (default `10000`, configurable with the `MAX_CONTEXT_SIZE` environment variable; see [Settings](../settings.md)). When a context is full, the **least-read** mapping is evicted to make room. A value whose mapping has been evicted is treated as new the next time it is seen and receives a fresh replacement.

## Interaction with replacement strategies

Replacement scope matters only for strategies that generate a value that would otherwise vary from run to run. Deterministic strategies are already consistent across documents regardless of scope.

| Strategy | Effect of `DOCUMENT` scope | Effect of `CONTEXT` scope |
| -------- | -------------------------- | ------------------------- |
| `RANDOM_REPLACE` (pseudonymize) | A value gets one randomly generated surrogate for every occurrence in the document. The next document gets a new one. | **Primary use.** The randomly generated surrogate is stored and reused, so the same value maps to the same surrogate across documents. |
| `MAP_REPLACE` | A value the lookup table does not map gets one generated replacement for every occurrence in the document, and the generator is asked once for it. | A value the lookup table does not map gets a generated replacement, which is stored and reused, so it maps to the same replacement across documents. |
| `FPE_ENCRYPT_REPLACE` | No table needed. Format-preserving encryption is already deterministic for a given key, so the same input always encrypts to the same value. See [FPE](../policies/filter_strategies.md#the-fpe_encrypt_replace-filter-strategy). | Same as `DOCUMENT`. |
| `HASH_SHA256_REPLACE` | Deterministic on its own (unless salted), so it is consistent regardless of scope. | Same as `DOCUMENT`. |
| `STATIC_REPLACE`, `MASK`, `REDACT` | Produce a fixed output for a given input, so scope has no effect. | Same as `DOCUMENT`. |

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

## Random date shifts

A date filter strategy of `SHIFT` with `"shiftRandom": true` draws one random shift for each replacement scope, for the first date it sees, and moves every date in that scope by the same number of days, so dates keep their order and the number of days between them. See [Dates](../policies/filters/common_filters/dates.md).

* With the default `DOCUMENT` scope, with or without a context, every date in one document is moved by the same number of days, and each document gets its own shift. The shift is kept only for the request.
* With `"replacementScope": "CONTEXT"`, one shift is drawn for the context and every date redacted in that context is moved by it, within a document and across documents.

Philter stores a `CONTEXT`-scoped shift with the context, not as a mapping:

* It is not evicted when the context reaches `MAX_CONTEXT_SIZE`, and does not count toward it.
* It does not appear in `GET /api/contexts/{name}/entries` and cannot be deleted through the entry endpoints.
* [Clearing the context's mappings](contexts.md#clearing-a-context) keeps it, so later dates still line up with earlier ones. [Deleting the context](contexts.md#deleting-a-context) removes it, and a context created again with the same name draws a new shift.
* [Export and import](../api_and_sdks/api/contexts_api.md#export-a-contexts-mapping-table) carry it, in the export's `values`. Treat an export that holds it as you would the original dates.

The shift applies to every subject redacted in the context. If one known original date reveals the shift, it reveals every date in the context, so use a separate context per subject (for example, per patient) when each needs its own shift.

## Not the same as disambiguation scope

Replacement scope is distinct from **[disambiguation scope](disambiguation.md)**, despite the similar document and context wording:

* **Replacement scope** (this page) is set **per filter strategy** in the policy and controls whether *generated replacements* are reused across documents.
* **Disambiguation scope** is set **on the context**, with its `disambiguationScope` setting (`document` or `context`), and controls whether Philter shares *entity-type disambiguation* knowledge across documents to improve detection accuracy.

## See Also

* [Redaction Contexts](contexts.md)
* [Filter Strategies](../policies/filter_strategies.md)
* [Disambiguation](disambiguation.md)
