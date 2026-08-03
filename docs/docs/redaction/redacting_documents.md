# Redacting Documents and Text

Document redaction is the core capability of Philter. It involves the sophisticated process of identifying, classifying, and removing or masking sensitive information — collectively known as Personally Identifiable Information (PII) and Protected Health Information (PHI)—from your digital files.

Our platform is engineered to handle a variety of common document formats, ensuring that your sensitive data is protected regardless of how it is stored. Every redaction operation is governed by a user-defined [redaction policy](policies.md) and organized within a specific [context](contexts.md).

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter provides specialized processing for the following document types:

*   **Microsoft Word (.docx)**: Word documents are processed with high precision. Our engine parses the document paragraph by paragraph, identifying sensitive entities within the text. You have the flexibility to choose how redactions are represented in the output, such as highlighting changes or enabling "Track Changes" (revisions) via your [policy settings](../policies/policy_schema.md).
*   **PDF (.pdf)**: PDF files are processed to ensure that sensitive information is visually and data-level obscured. Redactions are typically applied by drawing opaque black boxes over the identified sensitive text, preventing both visual recognition and digital extraction.
*   **Plain Text (.txt)**: For simple text files, the identified sensitive information is replaced with a placeholder or a masked value as defined in your policy (e.g., replacing a name with `[PERSON]` or a phone number with `[PHONE-NUMBER]`).

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction, it undergoes a rigorous multi-step workflow designed for security and accuracy:

1.  **Secure Upload**: The document is securely transmitted and uploaded to dedicated, encrypted storage associated with your account.
2.  **Automated MIME Type Detection**: Our system automatically analyzes the file to determine its MIME type. This ensures that the correct processing engine (Word, PDF, or Text) is used for optimal results.
3.  **Policy-Driven Identification**: The engine applies your selected [redaction policy](policies.md). This policy contains the logic and rules used to scan the document's content and identify PII/PHI.
4.  **Redacted Document Generation**: Based on the identification results, a new version of the document is generated. This version has all sensitive information removed or masked according to your policy's specifications.
5.  **Generation of Summary Report**: For every processed document, a comprehensive **Redaction Summary** (in PDF format) is generated. This report provides a high-level overview of the operation, including the number of tokens analyzed and the total count of redactions performed.
6.  **Audit Logging and Ledgering**: If the [redaction ledger](ledgers.md) is enabled for the context, the redaction events are recorded in the cryptographic ledger to provide a detailed audit trail of every modification made to the document.

## How to Redact a Document via the Dashboard

The dashboard redacts a document directly, for testing a policy before you use the API. It is not a
document management surface: nothing is queued and nothing is stored.

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

## Advanced Redaction Features

### Detailed Summary Documents

The generated **Redaction Summary** is a vital tool for compliance and auditing. Each summary includes:

*   **Metadata**: Original filename, unique document ID, and the timestamp of the operation.
*   **Operational Details**: The specific Context and Policy applied to the document.
*   **Statistics**: The total token count (volume of data) and the total number of redactions (volume of sensitive info removed).

### Immutable Cryptographic Ledgers

To ensure the highest level of integrity and non-repudiation, Philterd maintains a [cryptographic ledger](ledgers.md) for plain text redactions. Every redaction is recorded as a cryptographically-signed entry in an immutable log, providing verifiable proof that the data was processed according to your requirements and has not been tampered with since.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Schema Reference](../policies/policy_schema.md) - A deep dive into the JSON structure of redaction policies.
