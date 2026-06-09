# Redaction Ledgers

A Redaction Ledger is a core security feature of Philter, providing a cryptographically-verifiable and immutable log of every redaction performed on your documents. In an era where data integrity and transparency are paramount, ledgers offer a definitive way to audit and trust the automated redaction process.

By maintaining a verifiable record of what was changed, why it was changed, and how it was changed, Philter empowers your organization to demonstrate compliance with rigorous data privacy standards like HIPAA, GDPR, and CCPA. The chain is **tamper-evident** — entries cannot be altered without detection — but, as described under [How and When Ledger Entries Are Deleted](#how-and-when-ledger-entries-are-deleted), entries can be removed deliberately for data-minimization or lifecycle reasons.

## How Redaction Ledgers Work

The Philterd ledger system is built on the principles of cryptographic chaining, similar to a blockchain. 

1.  **Granular Recording**: When a document is processed, every single instance of identified PII or PHI is recorded as an individual entry in that document's specific ledger.
2.  **Cryptographic Chaining**: Each ledger entry contains a cryptographic hash of its own data plus the hash of the preceding entry. This creates a "chain of trust."
3.  **Immutability**: Because each entry is linked to the previous one, any attempt to retroactively modify or delete a redaction record would break the cryptographic chain, making the tampering immediately evident.
4.  **Verifiability**: This architecture allows you to mathematically prove the integrity of your redaction history at any point in time.

## Which Policy Version Governed a Redaction

Each ledger entry records the **governing policy**: its name, its version (the policy's revision at the time), and a SHA-256 fingerprint of the exact policy content that was applied. These three values are part of the entry's tamper-evident hash, so the stamped policy cannot be altered without breaking the chain.

To make that version stamp resolvable to real content, Philter retains an **immutable, append-only snapshot** of a policy's content every time the policy is saved. Snapshots are content-addressed by their fingerprint, so:

* a deleted-then-recreated policy that reuses a name never collides with prior evidence, and
* editing or deleting the live policy never removes the retained snapshots. Retained versions are evidence, separate from the live policy lifecycle; removing them is a separate, deliberate, audited action.

This lets an administrator take any redaction in a date range and produce the exact policy version that was in force for it, with no engineering work, and verify the ledger chain is intact. Even if a snapshot is later removed under a retention action, the ledger entry still proves which policy was applied by its name, version, and fingerprint; only the ability to render that policy's content is lost.

The applied policy name and version are also returned at redaction time, on the `X-Philter-Policy-Name` and `X-Philter-Policy-Version` response headers of `/api/filter` and in the `/api/explain` response body. See the [Filtering API](../api_and_sdks/api/filtering_api.md).

## Enabling Redaction Ledgers

Redaction ledgers are controlled on a per-context basis. When creating or editing a [context](contexts.md), use the **Enable the redaction ledger** option to turn the ledger on for that context. The option is unchecked (disabled) by default, so a new context does not record a ledger until you enable it. Redactions performed in a context with the ledger enabled are recorded; redactions in a context with it disabled are not.

## How and When Ledger Entries Are Deleted

**By default, ledger entries are kept indefinitely.** Because the ledger is a tamper-evident audit record, Philter never deletes entries on its own unless you opt in. There are three ways entries are removed, summarized below.

Deletion always operates on **whole document chains**, never on individual entries within a chain. This preserves verifiability: a chain that remains is always complete and can still be validated, and a chain that is removed is removed in its entirety.

**Legal holds block all three deletion paths.** If a [legal hold](legal_holds.md) is active on a document chain or a user's evidence, every deletion attempt against that evidence is blocked and returns HTTP 423, regardless of which deletion path is used. The hold must be released before any deletion can proceed. See [Legal Holds](legal_holds.md) for the full documentation.

### 1. Manual purge (on demand)

You can prune old entries yourself at any time. This is the primary way to enforce a retention policy.

* **Dashboard**: on the **Ledger** page, use **Purge old entries** and enter a number of days. Every chain of yours older than that is deleted.
* **API**: `DELETE /api/ledger?older_than_days={n}` deletes the calling user's chains older than `n` days. See the [Ledger API](../api_and_sdks/api/ledger_api.md#purge-old-ledger-entries).

### 2. Deleting a single document's chain

* **Dashboard**: click the delete (trash) icon next to a document on the **Ledger** page.
* **API**: `DELETE /api/ledger/{documentId}` removes that document's chain. See the [Ledger API](../api_and_sdks/api/ledger_api.md#delete-a-documents-ledger-chain).

### 3. Automatic expiry (optional, off by default)

If you want time-based expiry without running a manual purge, set the `REDACTION_LEDGER_TTL_DAYS` environment variable (see [Settings](../settings.md)) to a positive number of days. MongoDB then automatically expires entries older than that. It is **`0` (no expiry) by default**. If a deployment previously configured a TTL and you set the variable back to `0`, Philter drops the existing expiry index on startup so entries stop being auto-deleted.

### Ledger entries survive user deactivation

Deactivating a user account does **not** delete that user's ledger. Users are deactivated rather than deleted (see [User Management](../dashboard.md#user-management)), and deactivation never cascades to the ledger: every chain is retained and stays resolvable to the retained (deactivated) owning user, so the redaction evidence is preserved. The only ways ledger entries are removed are the three above (a deliberate manual purge, a single-chain delete, or optional automatic expiry).

## Exporting Ledger Entries

A document's full ledger chain can be exported as a portable JSON document so it can be archived externally and later re-verified — each exported entry carries its `hash` and `previousHash`, so the chain's integrity can be checked offline.

* **Dashboard**: open a document's ledger with **View**, then use **Export (JSON)** to download the chain.
* **API**: `GET /api/ledger/{documentId}/export` returns the chain as a downloadable JSON document. See the [Ledger API](../api_and_sdks/api/ledger_api.md#export-a-documents-ledger-chain).

> **Security:** unlike a context export (which contains only token hashes), a ledger export includes the **decrypted original token and its replacement**, because the ledger's purpose is to record exactly what was redacted to what. Treat an export as sensitive and store and transmit it securely.

## The Redaction Ledgers Dashboard

The **Ledgers** page within your Philterd dashboard serves as the central hub for auditing your document processing activities.

### The Recent Documents List

By default, the page displays the most recently processed documents (up to 100) that have ledgering enabled. For each document, the following metadata is provided:
  
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
*   **Dashboard Listing**: The main dashboard view shows the most recent documents (up to 100). All ledger data — including older chains beyond that listing — remains accessible via the [Ledger API](../api_and_sdks/api/ledger_api.md) for historical reporting. Entries are retained until you remove them (see [How and When Ledger Entries Are Deleted](#how-and-when-ledger-entries-are-deleted)).
*   **Data Privacy**: Access to ledgers is highly restricted and requires appropriate account permissions, as ledgers contain records of the original sensitive information (the "Identified Token"). A regular user can only access their own ledger; an administrator can access any user's ledger through the [Ledger API](../api_and_sdks/api/ledger_api.md) by supplying the owner's email.

