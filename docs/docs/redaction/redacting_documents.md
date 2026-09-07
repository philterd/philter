# Redacting Documents and Text

Document redaction is the core capability of Philter. It identifies, classifies, and removes or masks sensitive information (Personally Identifiable Information and Protected Health Information) in the content you submit.

Every redaction is governed by a user-defined [redaction policy](policies.md) and can be organized within a [context](contexts.md). Detection is probabilistic, so validate Philter's output against your own data before relying on it.

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter redacts the following content types, selected by the request's `Content-Type`:

*   **Plain Text (`text/plain`)**: Identified information is replaced according to the [filter strategy](../policies/filter_strategies.md) in your policy, for example redaction, masking, or encryption.
*   **PDF (`application/pdf`)**: The engine detects supported page text and obscures matched regions in the output. PDF redaction is [asynchronous by default](../api_and_sdks/api/documents_api.md). Sensitive content in FreeText annotations can survive processing; a successful response does not certify that every PDF surface has been redacted. Review both rendered output and extractable text, including annotations. Philter does not implement OCR or redact image-only PDFs.

Philter does not redact Microsoft Word (`.docx`), other Office formats, or images.

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction:

1.  **Submission**: The document is sent to `POST /api/filter`. Its `Content-Type` selects the processing engine; Philter does not inspect the bytes to guess the format.
2.  **Policy-driven identification**: The engine applies the selected [redaction policy](policies.md), which defines what counts as sensitive and how each type is handled.
3.  **Redacted output**: A redacted copy is produced. Text is returned in the response; PDFs are queued and retrieved from the [Documents API](../api_and_sdks/api/documents_api.md) when processing finishes.
4.  **Ledgering**: If the [redaction ledger](ledgers.md) is enabled for the context, each redaction is recorded as a hash-chained entry stamped with the policy version that governed it.

Queued PDF jobs retain encrypted input while pending or processing and remove it when the job reaches a terminal state. Results and job records have a separate [retention period](../settings.md#asynchronous-documents-and-webhooks); captured execution configurations are retained separately.

Synchronous text redaction does not retain the complete submitted document as a queued job. Depending on the context and configuration, it can persist token-to-replacement mappings, encrypted original and replacement values in the ledger, and audit or usage records. See [Database](../database.md) for what is stored and encrypted.

## How to Redact a Document via the Dashboard

The dashboard redacts a document directly, for testing a policy before you use the API. It is not a
document management surface: it processes input synchronously without a context, so it does not queue a document or create context mappings or a context ledger. The input and downloadable result are held in memory for the test. Redaction metrics and configured usage reporting can still be emitted.

1.  **Open the Dashboard.** Log in and select **Dashboard** in the left-hand navigation. The
    **Redaction Test** tab is where redaction runs.
2.  **Select a redaction policy** from the policy list for PDF redaction.
3.  **Upload the PDF** with the upload control.
4.  **Click Submit PDF.** The document is redacted immediately.
5.  **Download the result** with the **Download redacted &lt;filename&gt;** link that appears.

To redact plain text instead, paste it into the text area, choose a policy, and click **Submit
Text**; the redacted text is shown in place.

For anything beyond testing, including batches, contexts, and asynchronous processing, use the
[Filtering API](../api_and_sdks/api/filtering_api.md) and the
[Documents API](../api_and_sdks/api/documents_api.md).

## Immutable Cryptographic Ledgers

Philter can maintain a [cryptographic ledger](ledgers.md) of the redactions made in a context that has the ledger enabled. Each redaction is recorded as an entry in a tamper-evident hash chain, stamped with the name, version, and content hash of the policy that governed it, so the chain can be verified later and shown not to have been altered.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Schema Reference](../policies/policy_schema.md) - A deep dive into the JSON structure of redaction policies.
