# Redaction Ledgers

A Redaction Ledger is a core security feature of Philter, providing a cryptographically-verifiable and immutable log of every redaction performed on your documents. In an era where data integrity and transparency are paramount, ledgers offer a definitive way to audit and trust the automated redaction process.

By maintaining a verifiable record of what was changed, why it was changed, and how it was changed, Philter empowers your organization to demonstrate compliance with rigorous data privacy standards like HIPAA, GDPR, and CCPA. The chain is **tamper-evident** and each entry is **signed**, so an altered entry is detectable and its origin is provable. See [What the Ledger Proves](#what-the-ledger-proves). As described under [How and When Ledger Entries Are Deleted](#how-and-when-ledger-entries-are-deleted), entries can still be removed deliberately for data-minimization or lifecycle reasons.

## How Redaction Ledgers Work

The Philterd ledger system is built on the principles of cryptographic chaining, similar to a blockchain. 

1.  **Granular Recording**: When a document is processed, every single instance of identified PII or PHI is recorded as an individual entry in that document's specific ledger.
2.  **Cryptographic Chaining**: Each ledger entry contains a cryptographic hash of its own data plus the hash of the preceding entry. This creates a "chain of trust."
3.  **Immutability**: Because each entry is linked to the previous one, any attempt to retroactively modify or delete a redaction record would break the cryptographic chain, making the tampering immediately evident.
4.  **Verifiability**: This architecture allows you to mathematically prove the integrity of your redaction history at any point in time.

## What the Ledger Proves

Two separate guarantees, which fail for different reasons and are reported separately by
`GET /api/ledger/{documentId}/valid`.

**The hash chain proves internal consistency.** Each entry contains the hash of the previous entry,
so altering an entry breaks every link after it. On its own this is not proof of origin: anyone able
to write to the database could rewrite an entry, recompute its hash, and relink the entries that
follow, producing a chain that verifies perfectly.

**The signature proves origin.** Every entry is signed with the deployment's ES256 key, so an entry
rewritten in the database cannot be re-signed without that key. Signing is always on and is not tied
to the [output signing](../output_signing.md) setting.

**How strong that is depends on where the key lives.** By default Philter generates the signing key
and stores it in MongoDB, in the same database as the ledger. Someone with full database access can
therefore read the key and re-sign a rewritten chain, so in that configuration the signature defends
against tampering through Philter or by anyone with write access to the `ledger` collection alone,
not against a full database compromise. To get the stronger property, supply the key from outside the
database with `PHILTER_SIGNING_KEY_PATH` (see [Output Signing](../output_signing.md)) and restrict who
can read that file.

The validity response reports both, plus how many entries carry a signature:

```json
{
  "documentId": "...",
  "valid": true,
  "hashChainValid": true,
  "signaturesValid": true,
  "signedEntries": 2,
  "unsignedEntries": 0
}
```

`unsignedEntries` counts entries written before signing existed. They cannot be signed after the
fact, so they are reported as unproven rather than treated as tampered.

**Verifying independently.** Each entry names the key that signed it in `signingKeyId`. Fetch that
key from `GET /api/signing-key/{keyId}`, which needs no authentication, then verify the ES256
signature over the entry's `hash`. A ledger export embeds the keys it needs in a `signingKeys` map,
so an exported chain verifies without reaching the instance that produced it.

**Key rotation preserves history.** Regenerating the signing key retains the superseded key rather
than deleting it, so entries signed with it stay verifiable. Without this, a single regeneration
would void the provenance of every entry ever written.

**What the ledger does not prove.** It does not prove that a redaction was correct, only what
Philter recorded. It also does not prevent deletion: an administrator with `LEDGER_DELETION_ENABLED`
can remove entries, and a [legal hold](legal_holds.md) is what blocks that.

## Which Policy Version Governed a Redaction

Each ledger entry records the **governing policy**: its name, its version (the policy's revision at the time), and a SHA-256 fingerprint of the exact policy content that was applied. These three values are part of the entry's tamper-evident hash, so the stamped policy cannot be altered without breaking the chain.

To make that version stamp resolvable to real content, Philter retains an **immutable, append-only snapshot** of a policy's content every time the policy is saved. Snapshots are content-addressed by their fingerprint, so:

* a deleted-then-recreated policy that reuses a name never collides with prior evidence, and
* editing or deleting the live policy never removes the retained snapshots. Retained versions are evidence, separate from the live policy lifecycle; removing them is a separate, deliberate, audited action.

This lets an administrator take any redaction in a date range and produce the exact policy version that was in force for it, with no engineering work, and verify the ledger chain is intact. Even if a snapshot is later removed under a retention action, the ledger entry still proves which policy was applied by its name, version, and fingerprint; only the ability to render that policy's content is lost.

The applied policy name and version are also returned at redaction time, on the `X-Philter-Policy-Name` and `X-Philter-Policy-Version` response headers of `/api/filter` and in the `/api/explain` response body. See the [Redaction API](../api_and_sdks/api/filtering_api.md).

## Enabling Redaction Ledgers

Redaction ledgers are controlled on a per-context basis. When creating or editing a [context](contexts.md), use the **Enable the redaction ledger** option to turn the ledger on for that context. The option is unchecked (disabled) by default, so a new context does not record a ledger until you enable it. Redactions performed in a context with the ledger enabled are recorded; redactions in a context with it disabled are not.

## How and When Ledger Entries Are Deleted

**Ledger entries never expire on their own.** Because the ledger is a tamper-evident audit record, nothing removes an entry except a deliberate deletion by an administrator. There are two, described below.

Deletion always operates on **whole document chains**, never on individual entries within a chain. This preserves verifiability: a chain that remains is always complete and can still be validated, and a chain that is removed is removed in its entirety.

**Legal holds block both deletion paths.** If a [legal hold](legal_holds.md) is active on a document chain or a user's evidence, a purge or a single-chain delete against that evidence is blocked and returns HTTP 423. The hold must be released before either can proceed. Because these are the only ways entries are removed, a hold is an absolute guarantee that the evidence it covers is preserved. See [Legal Holds](legal_holds.md) for the full documentation.

> **Deletion is restricted.** Both paths require an **administrator** and `LEDGER_DELETION_ENABLED=true`, which is **`false` by default**. A deployment that has not opted in cannot delete ledger evidence through Philter at all, and the deletion controls do not appear in the dashboard. See [Settings](../settings.md).

### 1. Purge by age (on demand or scheduled)

An administrator can prune old entries at any time. This is how you enforce a retention policy: schedule this call and you get time-based retention that is still admin-only, hold-aware, and audited.

* **Dashboard**: on the **Redaction Ledgers** page, use **Purge old entries** and enter a number of days. Every chain of yours older than that is deleted.
* **API**: `DELETE /api/ledger?older_than_days={n}` deletes the calling user's chains older than `n` days. See the [Ledger API](../api_and_sdks/api/ledger_api.md#purge-old-ledger-entries).

### 2. Deleting a single document's chain

* **Dashboard**: click the delete (trash) icon next to a document on the **Redaction Ledgers** page.
* **API**: `DELETE /api/ledger/{documentId}` removes that document's chain. See the [Ledger API](../api_and_sdks/api/ledger_api.md#delete-a-documents-ledger-chain).

### There is no automatic expiry

Earlier builds offered a `REDACTION_LEDGER_TTL_DAYS` variable that had MongoDB expire old entries. It was removed. Because MongoDB performs that deletion itself, it could not check legal holds and left no audit record, which is the opposite of what evidence retention requires. Schedule the purge above instead. If a deployment set the variable, Philter drops the leftover index at startup and logs that it has done so.

### Ledger entries survive user deactivation

Deactivating a user account does **not** delete that user's ledger. Users are deactivated rather than deleted (see [User Management](../dashboard.md#user-management)), and deactivation never cascades to the ledger: every chain is retained and stays resolvable to the retained (deactivated) owning user, so the redaction evidence is preserved. The only ways ledger entries are removed are the two above.

## Exporting Ledger Entries

A document's full ledger chain can be exported as a portable JSON document so it can be archived externally and later re-verified — each exported entry carries its `hash` and `previousHash`, so the chain's integrity can be checked offline.

* **Dashboard**: open a document's ledger with **View**, then use **Export (JSON)** to download the chain.
* **API**: `GET /api/ledger/{documentId}/export` returns the chain as a downloadable JSON document. See the [Ledger API](../api_and_sdks/api/ledger_api.md#export-a-documents-ledger-chain).

> **Security:** unlike a context export (which contains only token hashes), a ledger export includes the **decrypted original token and its replacement**, because the ledger's purpose is to record exactly what was redacted to what. Treat an export as sensitive and store and transmit it securely.

## The Redaction Ledgers Dashboard

The **Redaction Ledgers** page within your Philterd dashboard serves as the central hub for auditing your document processing activities.

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

*   **Plain text**: individual redactions are recorded as chain entries, so the ledger holds the full detail of what was redacted.
*   **PDF**: a chain is created and appears in the ledger listing, stamped with the filename and the governing policy version, but the individual redactions within the PDF are **not** recorded as entries. The chain validates, but it holds no per-redaction detail.
*   **Dashboard Listing**: The main dashboard view shows the most recent documents (up to 100). All ledger data, including older chains beyond that listing, remains accessible via the [Ledger API](../api_and_sdks/api/ledger_api.md) for historical reporting. Entries are retained until you remove them (see [How and When Ledger Entries Are Deleted](#how-and-when-ledger-entries-are-deleted)).
*   **Data Privacy**: Access to ledgers is highly restricted and requires appropriate account permissions, as ledgers contain records of the original sensitive information (the "Identified Token"). A regular user can only access their own ledger; an administrator can access any user's ledger through the [Ledger API](../api_and_sdks/api/ledger_api.md) by supplying the owner's email.

