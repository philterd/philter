# Redacting Documents and Text

Document redaction is the core capability of Philter. It identifies, classifies, and removes or masks sensitive information (Personally Identifiable Information and Protected Health Information) in the content you submit.

Every redaction is governed by a user-defined [redaction policy](policies.md) and can be organized within a [context](contexts.md). Detection is probabilistic, so validate Philter's output against your own data before relying on it.

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter redacts the following content types, selected by the request's `Content-Type`:

*   **Plain Text (`text/plain`)**: Identified information is replaced according to the [filter strategy](../policies/filter_strategies.md) in your policy, for example redaction, masking, or encryption.
*   **PDF (`application/pdf`)**: The engine detects supported page text and obscures matched regions in the output. PDF redaction is [asynchronous by default](../api_and_sdks/api/documents_api.md). Sensitive content in FreeText annotations can survive processing; a successful response does not certify that every PDF surface has been redacted. Review both rendered output and extractable text, including annotations. Philter does not implement OCR or redact image-only PDFs. The `Accept` header selects the output: `application/pdf` returns the redacted PDF, and `application/zip` returns a ZIP archive containing it as a single `redacted.pdf` entry.

*   **Word (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`)** and **Excel (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`)**: Identified information is replaced in the document according to the filter strategy, as for text, and the response is an editable document of the same format. Redaction is asynchronous by default, as for PDF. See [Word and Excel documents](#word-and-excel-documents).

Philter does not redact other Office formats (`.doc`, `.xls`, PowerPoint, templates, and macro-enabled files), OpenDocument files, or images.

## Word and Excel Documents

Office documents keep copies of text outside the visible body, so Philter redacts these as well:

*   **Word:** body paragraphs and tables, headers and footers, footnotes, endnotes, comments, text in shapes, text boxes, and SmartArt, chart text and cached chart values, hyperlink targets, field instructions, tracked deletions, and embedded Word and Excel documents.
*   **Excel:** text and number cells, the shared strings redacted cells leave behind, print headers and footers, comments and their authors, text in shapes and text boxes, chart text and cached chart values, cached formula results, pivot caches, and embedded Word and Excel documents.

Embedded objects that cannot be inspected, anything other than an embedded Word or Excel document or one that cannot be read, are removed. Parts of the document that redaction does not change are returned unchanged.

Limitations:

*   Document properties, such as the author in `docProps/core.xml`, are not redacted, and neither are the author names Word records on comments and tracked changes.
*   A Word paragraph that is redacted loses its inline formatting, and hyperlinks and fields in it become plain text.
*   A redacted Excel cell is written as text, and a redacted formula cell loses its formula. A workbook with a redacted cell recalculates when it is opened.
*   Excel cells are scanned by their stored value, not as Excel displays them. Excel stores most dates as serial numbers, so a date displayed as `3/14/1985` is scanned as `31120` and is not found by the date filter.
*   Only the Transitional Office Open XML format, which Word and Excel save by default, is supported. Password-protected documents and documents saved as Strict Open XML are refused.

A body declared as Word or Excel must be a document of that type: an Excel workbook sent as Word, or any other ZIP file, is refused with `415 Unsupported Media Type`. A document that decompresses to more than [`OFFICE_MAX_UNCOMPRESSED_BYTES`](../settings.md#redaction-engine), has more than `OFFICE_MAX_ENTRIES` entries, or has an entry larger than 100 KB that expands to more than 100 times its compressed size is refused with `413 Payload Too Large`.

In the [redaction ledger](ledgers.md), the start position of a redaction in a Word or Excel document is its offset within the paragraph, cell, or other piece of text it was found in, not within the whole document.

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction:

1.  **Submission**: The document is sent to `POST /api/filter`. Its `Content-Type` selects the processing engine. Philter checks the leading bytes against the declared type and rejects a body that contradicts it (for example, a PDF sent as `text/plain`) with `415 Unsupported Media Type`; it does not use the bytes to choose a different format.
2.  **Policy-driven identification**: The engine applies the selected [redaction policy](policies.md), which defines what counts as sensitive and how each type is handled.
3.  **Redacted output**: A redacted copy is produced. Text is returned in the response; PDF, Word, and Excel documents are queued and retrieved from the [Documents API](../api_and_sdks/api/documents_api.md) when processing finishes.
4.  **Ledgering**: If the [redaction ledger](ledgers.md) is enabled for the context, each redaction is recorded as a hash-chained entry stamped with the policy version that governed it.

Queued document jobs retain encrypted input while pending or processing and remove it when the job reaches a terminal state. Results and job records have a separate [retention period](../settings.md#asynchronous-documents-and-webhooks); captured execution configurations are retained separately.

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

A Word document or Excel workbook is sent the same way with its own `Content-Type`, and is returned in the same format:

```bash
curl -k -X POST "https://localhost:8080/api/filter?p=my-policy&async=false" \
  --data-binary @sample.docx \
  -H "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
  -H "Authorization: Bearer $API_KEY" \
  -o redacted.docx
```

Without `async=false`, a PDF, Word, or Excel document is queued and processed asynchronously; poll and download it with the [Documents API](../api_and_sdks/api/documents_api.md), or receive a [webhook](../api_and_sdks/api/webhooks.md) when it completes.

## Immutable Cryptographic Ledgers

Philter can maintain a [cryptographic ledger](ledgers.md) of the redactions made in a context that has the ledger enabled. Each redaction is recorded as an entry in a tamper-evident hash chain, stamped with the name, version, and content hash of the policy that governed it, so the chain can be verified later and shown not to have been altered.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Schema Reference](../policies/policy_schema.md) - A deep dive into the JSON structure of redaction policies.
