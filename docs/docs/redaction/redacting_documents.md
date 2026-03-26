# Redacting Documents and Text

Document redaction is the core capability of Philter. It involves the sophisticated process of identifying, classifying, and removing or masking sensitive information — collectively known as Personally Identifiable Information (PII) and Protected Health Information (PHI)—from your digital files.

Our platform is engineered to handle a variety of common document formats, ensuring that your sensitive data is protected regardless of how it is stored. Every redaction operation is governed by a user-defined [redaction policy](policies.md) and organized within a specific [context](contexts.md).

**Please try to not include any sensitive information in the file names of uploaded documents.**

## Supported File Formats

Philter provides specialized processing for the following document types:

*   **Microsoft Word (.docx)**: Word documents are processed with high precision. Our engine parses the document paragraph by paragraph, identifying sensitive entities within the text. You have the flexibility to choose how redactions are represented in the output, such as highlighting changes or enabling "Track Changes" (revisions) via your [policy settings](policy_syntax.md).
*   **PDF (.pdf)**: PDF files are processed to ensure that sensitive information is visually and data-level obscured. Redactions are typically applied by drawing opaque black boxes over the identified sensitive text, preventing both visual recognition and digital extraction.
*   **Plain Text (.txt)**: For simple text files, the identified sensitive information is replaced with a placeholder or a masked value as defined in your policy (e.g., replacing a name with `[PERSON]` or a phone number with `[PHONE-NUMBER]`).

## The Comprehensive Redaction Workflow

When you submit a document to Philter for redaction, it undergoes a rigorous multi-step workflow designed for security and accuracy:

1.  **Secure Upload**: The document is securely transmitted and uploaded to dedicated, encrypted storage associated with your account.
2.  **Automated MIME Type Detection**: Our system automatically analyzes the file to determine its MIME type. This ensures that the correct processing engine (Word, PDF, or Text) is used for optimal results.
3.  **Policy-Driven Identification**: The engine applies your selected [redaction policy](policies.md). This policy contains the logic and rules used to scan the document's content and identify PII/PHI.
4.  **Redacted Document Generation**: Based on the identification results, a new version of the document is generated. This version has all sensitive information removed or masked according to your policy's specifications.
5.  **Generation of Summary Report**: For every processed document, a comprehensive **Redaction Summary** (in PDF format) is generated. This report provides a high-level overview of the operation, including the number of tokens analyzed and the total count of redactions performed.
6.  **Audit Logging and Ledgering**: If enabled in your account, the redaction events are recorded. For supported formats, entries are made in the [cryptographic ledger](ledgers.md), and a [changeset](policy_syntax.md) is created to provide a detailed audit trail of every modification made to the document.

## How to Redact a Document via the Dashboard

The Philter dashboard provides an intuitive interface for managing your redaction tasks. To redact a document, follow these steps:

1.  **Access the Documents Section**: Log in to your Philterd dashboard and navigate to the **Redaction** menu on the sidebar, then select **Documents**.
2.  **Initiate Upload**: Click the **Upload Document** button located at the top of the page.
3.  **Select Your File**: In the upload dialog, choose the document you wish to process from your local computer.
4.  **Assign a Context**: Select an appropriate [context](contexts.md) from the dropdown menu. Contexts allow you to group related documents together for improved organization (e.g., by project, department, or client).
5.  **Select a Redaction Policy**: Choose the [redaction policy](policies.md) that defines the rules for this specific task.
6.  **Submit for Processing**: Click the **Submit** button. Your document will now be queued for processing.
7.  **Monitor Status**: You can track the progress of your document in the document list. The status will transition from `PENDING` to `PROCESSING`, and finally to `COMPLETED`.
8.  **Retrieve Your Results**: Once the status is `COMPLETED`, several actions become available:
    *   **Download Redacted File**: Click the **Download** icon to retrieve the protected version of your document.
    *   **View Summary Report**: Click the **Summary** icon to download the PDF report detailing the redaction statistics.
    *   **Audit via Ledger**: If applicable, you can view the [ledger](ledgers.md) entries to see a granular log of the redactions.

## Advanced Redaction Features

### Detailed Summary Documents

The generated **Redaction Summary** is a vital tool for compliance and auditing. Each summary includes:

*   **Metadata**: Original filename, unique document ID, and the timestamp of the operation.
*   **Operational Details**: The specific Context and Policy applied to the document.
*   **Statistics**: The total token count (volume of data) and the total number of redactions (volume of sensitive info removed).

### Changesets for Deep Auditing

For organizations requiring granular oversight, Philterd can generate **Changesets**. A changeset records the exact original text that was identified for redaction and its precise location within the document. This allows for a comprehensive human-in-the-loop review or forensic audit of the redaction process.

### Immutable Cryptographic Ledgers

To ensure the highest level of integrity and non-repudiation, Philterd maintains a [cryptographic ledger](ledgers.md) for plain text redactions. Every redaction is recorded as a cryptographically-signed entry in an immutable log, providing verifiable proof that the data was processed according to your requirements and has not been tampered with since.

## Related Documentation

*   [Understanding Redaction Policies](policies.md) - Learn how to define what gets redacted.
*   [Managing Contexts](contexts.md) - Organize your redaction workflows effectively.
*   [Utilizing Cryptographic Ledgers](ledgers.md) - Ensure the integrity of your redaction process.
*   [Policy Syntax Reference](policy_syntax.md) - A deep dive into the JSON structure of redaction policies.
