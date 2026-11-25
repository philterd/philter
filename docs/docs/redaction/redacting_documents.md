# Redacting Documents and Text

Document redaction is the core capability of Philter. It identifies, classifies, and removes or masks sensitive information (Personally Identifiable Information and Protected Health Information) in the content you submit.

Every redaction is governed by a user-defined [redaction policy](policies.md) and can be organized within a [context](contexts.md). Detection is probabilistic, so validate Philter's output against your own data before relying on it.

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter redacts the following content types, selected by the request's `Content-Type`:

*   **Plain Text (`text/plain`)**: Identified information is replaced according to the [filter strategy](../policies/filter_strategies.md) in your policy, for example redaction, masking, or encryption.
*   **PDF (`application/pdf`)**: The engine detects supported page text and obscures matched regions in the output. PDF redaction is [asynchronous by default](../api_and_sdks/api/documents_api.md). Sensitive content in FreeText annotations can survive processing; a successful response does not certify that every PDF surface has been redacted. Review both rendered output and extractable text, including annotations. Philter does not implement OCR or redact image-only PDFs. The `Accept` header selects the output: `application/pdf` returns the redacted PDF, and `application/zip` returns a ZIP archive containing it as a single `redacted.pdf` entry.

*   **Word (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`)** and **Excel (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`)**: Identified information is replaced in the document according to the filter strategy, as for text, and the response is an editable document of the same format. Redaction is asynchronous by default, as for PDF. See [Word and Excel documents](#word-and-excel-documents).

*   **Email (`message/rfc822`, `.eml`)** and **Outlook (`application/vnd.ms-outlook`, `.msg`)**: Headers and text bodies are redacted according to the filter strategy, as for text, and the response is a MIME message, `message/rfc822`, whatever the input. Redaction is asynchronous by default, as for PDF. See [Email messages](#email-messages).

Philter does not redact other Office formats (`.doc`, `.xls`, PowerPoint, templates, and macro-enabled files), OpenDocument files, or images.

## Word and Excel Documents

Office documents keep copies of text outside the visible body, so Philter redacts these as well:

*   **Word:** body paragraphs and tables, headers and footers, footnotes, endnotes, comments, text in shapes, text boxes, and SmartArt, chart text and cached chart values, hyperlink targets, field instructions, tracked deletions, and embedded Word and Excel documents.
*   **Excel:** text and number cells, the shared strings redacted cells leave behind, print headers and footers, comments, text in shapes and text boxes, chart text and cached chart values, cached formula results, pivot caches, and embedded Word and Excel documents.

Who wrote and edited a document is cleared whatever the policy, in embedded Word and Excel documents too, and is not run through detection. Word comment and tracked-change authors and Excel comment authors become `Redacted`, Word comment initials and `word/people.xml` are removed, Excel threaded-comment people lose their identifiers, and the creator, last modified by, company, and manager document properties are emptied.

Embedded objects that cannot be inspected, anything other than an embedded Word or Excel document or one that cannot be read, are removed. Parts of the document that redaction does not change are returned unchanged.

Limitations:

*   Document properties other than those cleared above, such as the title, subject, keywords, and custom properties, are not redacted.
*   A Word paragraph that is redacted loses its inline formatting, and hyperlinks and fields in it become plain text.
*   A redacted Excel cell is written as text, and a redacted formula cell loses its formula. A workbook with a redacted cell recalculates when it is opened.
*   Excel cells are scanned by their stored value, not as Excel displays them. Excel stores most dates as serial numbers, so a date displayed as `3/14/1985` is scanned as `31120` and is not found by the date filter.
*   Only the Transitional Office Open XML format, which Word and Excel save by default, is supported. Password-protected documents and documents saved as Strict Open XML are refused.

A body declared as Word or Excel must be a document of that type: an Excel workbook sent as Word, or any other ZIP file, is refused with `415 Unsupported Media Type`. A document that decompresses to more than [`OFFICE_MAX_UNCOMPRESSED_BYTES`](../settings.md#redaction-engine), has more than `OFFICE_MAX_ENTRIES` entries, or has an entry larger than 100 KB that expands to more than 100 times its compressed size is refused with `413 Payload Too Large`.

In the [redaction ledger](ledgers.md), the start position of a redaction in a Word or Excel document is its offset within the paragraph, cell, or other piece of text it was found in, not within the whole document.

## Email Messages

Philter redacts MIME messages (`.eml`) and Outlook messages (`.msg`). Both are returned as `.eml` (`message/rfc822`): an Outlook message cannot be written back in its own format, so it is converted. Request it with `Accept: message/rfc822` or `Accept: */*`; any other `Accept` is refused with `406 Not Acceptable`.

The policy's identifiers redact:

*   the `Subject`, `From`, `To`, and `Cc` headers, and
*   every text body part. An HTML body is redacted as markup, so values in attributes such as `mailto:` links are covered, and by its visible text.

A message nested in another, such as a forwarded message, is redacted the same way. From an Outlook message, the subject, sender, recipients, sent date, bodies, and attachments are carried over; its stored transport headers are not.

### Email settings in the policy

The policy's `config.email` decides what happens to other headers, attachments, and inline images, so two policies can handle the same message differently. A queued message is redacted with the settings of the policy version in force when it was accepted. The defaults are:

```json
{
   "config": {
      "email": {
         "headers": {
            "removeTechnical": true,
            "removeIdentity": true,
            "removeDate": false,
            "removeSubject": false
         },
         "attachments": "keep",
         "inlineImages": "keep"
      }
   }
}
```

| Setting | Default | Effect |
|---|---|---|
| `headers.removeTechnical` | `true` | Removes `Received`, `Return-Path`, `Message-ID`, `User-Agent`, `X-Mailer`, `X-MimeOLE`, `DKIM-Signature`, `Authentication-Results`, `Received-SPF`, `Autocrypt`, and every header beginning `X-` or `ARC-`. |
| `headers.removeIdentity` | `true` | Removes `Bcc`, `Reply-To`, `Sender`, and every header beginning `Resent-`. |
| `headers.removeDate` | `false` | Removes the `Date` header. |
| `headers.removeSubject` | `false` | Removes the `Subject` header instead of redacting it. |
| `attachments` | `keep` | `keep` leaves attachments as they are, **not inspected or redacted**. `remove` deletes them, including their filenames. `redact` redacts each PDF, Word (`.docx`), and Excel (`.xlsx`) attachment with the same policy, and removes any other attachment and any that cannot be redacted. |
| `inlineImages` | `keep` | `keep` leaves images embedded in the body as they are, not inspected. `remove` deletes them. |

Other headers are kept as they are. Under the default `attachments: keep`, an attachment can contain sensitive information that is returned unchanged; use `remove` or `redact` when that matters. A PDF attachment under `redact` is redacted as a PDF is, with the policy's `config.pdf`, so a page with a redaction is flattened to an image. An attachment is recognized as PDF, Word, or Excel by its media type or filename extension. Under `attachments: remove`, a forwarded message attached to the message is removed too; under `keep` and `redact`, it is redacted as a nested message.

Limitations:

*   A signed message (S/MIME or PGP) is redacted and its signature is removed, since redacting the message invalidates it.
*   An encrypted message cannot be read, so it is refused with `400 Bad Request` and a message saying so, and nothing is returned. An S/MIME or PGP/MIME encrypted message is refused when it is submitted, queued or not. A message whose body is PGP-encrypted text is found only when it is redacted, so if it was queued, its job fails with that message.
*   Philter does not report which attachments or inline images were kept uninspected, or which were removed under `redact` because they could not be redacted.

A body declared as `application/vnd.ms-outlook` must be an Outlook message: a legacy Word or Excel file, which shares its container format, is refused with `415 Unsupported Media Type`. A body declared as `message/rfc822` must parse as a MIME message with a block of message headers, and is refused with `415` otherwise. A message larger than [`EMAIL_MAX_BYTES`](../settings.md#redaction-engine), with an attachment larger than `EMAIL_MAX_ATTACHMENT_BYTES`, with more than `EMAIL_MAX_ATTACHMENTS` attachments, or with messages nested more than `EMAIL_MAX_NESTING_DEPTH` deep is refused with `413 Payload Too Large` before redaction starts.

In the [redaction ledger](ledgers.md), the start position of a redaction in an email message is its offset within the header or body part it was found in. Redactions in attachments under `redact` are recorded as well. Removed headers, attachments, and inline images are not detections and are not recorded.

## Different Policies for Different Pages of a PDF

A PDF that bundles different documents, such as a cover letter followed by medical records, can be redacted with a different policy for each set of pages in one request. Add a `page_policy` parameter, `<pages>:<policy>`, for each page set:

```bash
curl -k -X POST "https://localhost:8080/api/filter?p=my-policy&page_policy=2-5,8:records&page_policy=12-:exhibits&async=false" \
  --data-binary @bundle.pdf \
  -H "Content-Type: application/pdf" \
  -H "Accept: application/pdf" \
  -H "Authorization: Bearer $API_KEY" \
  -o redacted.pdf
```

Pages are numbered from 1. A page set is single pages (`3`), ranges (`2-5`), and open-ended ranges (`12-`, page 12 to the end), separated by commas. A request can name up to 50 page sets.

*   **Unassigned pages** are redacted with the policy named by `p`, so every page is redacted under some policy.
*   **Filters and bounding boxes.** Each page's text is redacted with the filters and strategies of its page set's policy. A policy's bounding boxes are drawn only on the pages it is assigned to; those of the `p` policy only on unassigned pages.
*   **Context.** One [context](contexts.md) spans the document, so where two policies both use the `CONTEXT` replacement scope, a value gets the same replacement on pages under either policy.
*   **Rendering.** The `config.pdf` settings of the `p` policy, such as `dpi` and `redactionColor`, render the whole document. A strategy's own `color` still applies.
*   **Evidence.** The `X-Philter-Page-Policies` response header names the policy and version that redacted each page set, and each [ledger](ledgers.md) entry is stamped with the policy of the page it was found on. A queued PDF is redacted with the version of each policy in force when it was accepted.
*   **Validation.** A malformed page set, page sets that overlap, a page set beyond the document's last page, or `page_policy` on a document that is not a PDF is refused with `400 Bad Request`, and a policy that does not exist with `404 Not Found`, before anything is redacted.

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction:

1.  **Submission**: The document is sent to `POST /api/filter`. Its `Content-Type` selects the processing engine. Philter checks the leading bytes against the declared type and rejects a body that contradicts it (for example, a PDF sent as `text/plain`) with `415 Unsupported Media Type`; it does not use the bytes to choose a different format.
2.  **Policy-driven identification**: The engine applies the selected [redaction policy](policies.md), which defines what counts as sensitive and how each type is handled.
3.  **Redacted output**: A redacted copy is produced. Text is returned in the response; PDF, Word, Excel, and email documents are queued and retrieved from the [Documents API](../api_and_sdks/api/documents_api.md) when processing finishes.
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

An Outlook message is sent as `application/vnd.ms-outlook` and returned as `message/rfc822`:

```bash
curl -k -X POST "https://localhost:8080/api/filter?p=my-policy&async=false" \
  --data-binary @sample.msg \
  -H "Content-Type: application/vnd.ms-outlook" \
  -H "Accept: message/rfc822" \
  -H "Authorization: Bearer $API_KEY" \
  -o redacted.eml
```

Without `async=false`, a PDF, Word, Excel, or email document is queued and processed asynchronously; poll and download it with the [Documents API](../api_and_sdks/api/documents_api.md), or receive a [webhook](../api_and_sdks/api/webhooks.md) when it completes.

## Immutable Cryptographic Ledgers

Philter can maintain a [cryptographic ledger](ledgers.md) of the redactions made in a context that has the ledger enabled. Each redaction is recorded as an entry in a tamper-evident hash chain, stamped with the name, version, and content hash of the policy that governed it, so the chain can be verified later and shown not to have been altered.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Schema Reference](../policies/policy_schema.md) - A deep dive into the JSON structure of redaction policies.
