# Redaction Ledgers

A Redaction Ledger is a core security feature of Philterd Data Services, providing a cryptographically-verifiable and immutable log of every redaction performed on your documents. In an era where data integrity and transparency are paramount, ledgers offer a definitive way to audit and trust the automated redaction process.

By maintaining a permanent record of what was changed, why it was changed, and how it was changed, Philterd Data Services empowers your organization to demonstrate compliance with rigorous data privacy standards like HIPAA, GDPR, and CCPA.

## How Redaction Ledgers Work

The Philterd ledger system is built on the principles of cryptographic chaining, similar to a blockchain. 

1.  **Granular Recording**: When a document is processed, every single instance of identified PII or PHI is recorded as an individual entry in that document's specific ledger.
2.  **Cryptographic Chaining**: Each ledger entry contains a cryptographic hash of its own data plus the hash of the preceding entry. This creates a "chain of trust."
3.  **Immutability**: Because each entry is linked to the previous one, any attempt to retroactively modify or delete a redaction record would break the cryptographic chain, making the tampering immediately evident.
4.  **Verifiability**: This architecture allows you to mathematically prove the integrity of your redaction history at any point in time.

## The Redaction Ledgers Dashboard

The **Ledgers** page within your Philterd dashboard serves as the central hub for auditing your document processing activities.

### The Recent Documents List

By default, the page displays a list of the 50 most recently processed documents that have ledgering enabled. For each document, the following metadata is provided:
  
* **Original File Name**: The name of the document as it was uploaded.
*   **Unique Document ID**: A system-generated UUID that uniquely identifies this specific redaction task.
*   **Processing Date/Time**: A precise timestamp indicating when the redaction operation was completed.

To delve deeper into the audit trail for a specific file, click the **View Ledger** button associated with that document.

### The Detailed Redaction Log

Opening a document's ledger reveals a line-by-line accounting of the sensitive information handled by the engine: 

* **Timestamp**: The exact millisecond the specific redaction was committed to the ledger.
*   **Cryptographic Hash**: The unique SHA-256 (or similar) hash for this specific entry, ensuring its place in the chain.
*   **Identified Token**: The original, sensitive text that was detected (e.g., "John Smith").
*   **Applied Replacement**: The redacted or masked value that replaced the original token (e.g., `[PERSON]` or `********`).
*   **Classification (Type)**: The category of PII/PHI identified, corresponding to your policy's filters (e.g., `SSN`, `EMAIL_ADDRESS`, `PHONE_NUMBER`).

## Searching and Filtering

For organizations processing a high volume of documents, you can quickly locate a specific audit trail by utilizing the search box at the bottom of the Ledgers page. Simply enter all or part of the file name or the Document ID to filter the list.

## Important Considerations and Limitations

*   **Supported File Formats**: To maintain high performance and accuracy, cryptographic ledgers are currently supported for:
    *   **Plain Text (.txt)** documents.
    *   **Microsoft Word (.docx)** documents.
*   **PDF Support**: At this time, redaction ledgers are **not** generated for PDF documents. We recommend using the PDF Redaction Summary report for auditing PDF workflows.
*   **Dashboard Retention**: The main dashboard view focuses on the most recent 50 documents. However, all ledger data is securely archived and remains accessible via the API for comprehensive historical reporting.
*   **Data Privacy**: Access to ledgers is highly restricted and requires appropriate account permissions, as ledgers contain records of the original sensitive information (the "Identified Token").

