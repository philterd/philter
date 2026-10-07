# Redacting Documents and Text

Document redaction is the core capability of Philter. It identifies, classifies, and removes or masks sensitive information (Personally Identifiable Information and Protected Health Information) in the content you submit.

Every redaction is governed by a user-defined [redaction policy](policies.md) and can be organized within a [context](contexts.md). Detection is probabilistic, so validate Philter's output against your own data before relying on it.

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter redacts the following content types, selected by the request's `Content-Type`:

*   **Plain Text (`text/plain`)**: Identified information is replaced according to the [filter strategy](../policies/filter_strategies.md) in your policy, for example redaction, masking, or encryption.
*   **PDF (`application/pdf`)**: The engine detects supported page text and obscures matched regions in the output. PDF redaction is [asynchronous by default](../api_and_sdks/api/documents_api.md). Sensitive content in FreeText annotations can survive processing; a successful response does not certify that every PDF surface has been redacted. Review both rendered output and extractable text, including annotations. Philter does not implement OCR or redact image-only PDFs. The `Accept` header selects the output: `application/pdf` returns the redacted PDF, and `application/zip` returns a ZIP archive containing it as a single `redacted.pdf` entry.

Philter does not redact Microsoft Word (`.docx`), other Office formats, or images.

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction:

1.  **Submission**: The document is sent to `POST /api/filter`. Its `Content-Type` selects the processing engine. Philter checks the leading bytes against the declared type and rejects a body that contradicts it (for example, a PDF sent as `text/plain`) with `415 Unsupported Media Type`; it does not use the bytes to choose a different format.
2.  **Policy-driven identification**: The engine applies the selected [redaction policy](policies.md), which defines what counts as sensitive and how each type is handled.
3.  **Redacted output**: A redacted copy is produced. Text is returned in the response; PDFs are queued and retrieved from the [Documents API](../api_and_sdks/api/documents_api.md) when processing finishes.
4.  **Ledgering**: If the [redaction ledger](ledgers.md) is enabled for the context, each redaction is recorded as a hash-chained entry stamped with the policy version that governed it.

Queued PDF jobs retain encrypted input while pending or processing and remove it when the job reaches a terminal state. Results and job records have a separate [retention period](../settings.md#asynchronous-documents-and-webhooks); captured execution configurations are retained separately.

Synchronous text redaction does not retain the complete submitted document as a queued job. Depending on the context and configuration, it can persist token-to-replacement mappings, encrypted original and replacement values in the ledger, and audit or usage records. See [Database](../database.md) for what is stored and encrypted.

## How to Redact a Document

Documents are redacted with the [Filtering API](../api_and_sdks/api/filtering_api.md). Name the policy with `p` and, optionally, a context with `c`.

To test a policy on plain text, send it synchronously and read the redacted text from the response:

```bash
curl -k -X POST "https://localhost:8080/api/filter?p=my-policy" \
  --data-binary @sample.txt \
  -H "Content-Type: text/plain" \
  -H "Authorization: Bearer $API_KEY"
```

To redact a PDF and receive the result inline, add `async=false`:

```bash
curl -k -X POST "https://localhost:8080/api/filter?p=my-policy&async=false" \
  --data-binary @sample.pdf \
  -H "Content-Type: application/pdf" \
  -H "Accept: application/pdf" \
  -H "Authorization: Bearer $API_KEY" \
  -o redacted.pdf
```

Without `async=false`, a PDF is queued and processed asynchronously; poll and download it with the [Documents API](../api_and_sdks/api/documents_api.md), or receive a [webhook](../api_and_sdks/api/webhooks.md) when it completes.

## Immutable Cryptographic Ledgers

Philter can maintain a [cryptographic ledger](ledgers.md) of the redactions made in a context that has the ledger enabled. Each redaction is recorded as an entry in a tamper-evident hash chain, stamped with the name, version, and content hash of the policy that governed it, so the chain can be verified later and shown not to have been altered.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Schema Reference](../policies/policy_schema.md) - A deep dive into the JSON structure of redaction policies.
