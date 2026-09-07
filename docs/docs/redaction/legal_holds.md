# Legal Holds

A **legal hold** is a named, audited instruction to Philter to never delete the redaction evidence for a specific scope of data. Legal holds are the primary mechanism for preserving governance evidence during litigation, regulatory investigation, or any situation where data must not be destroyed.

When a legal hold is active on a user's data, every deletion path in Philter is blocked. A blocked operation returns an **HTTP 423 Locked** response and the attempt is written to the audit log. The hold must be explicitly released before any deletion proceeds.

## Why Legal Holds Exist

Redaction ledgers record a tamper-evident history of every redaction performed on every document. This history is normally deletable: an administrator can purge old entries on demand or delete individual document chains. For most purposes that is fine, but when evidence is needed for a legal or regulatory matter, accidental or routine deletion would destroy it.

Legal holds block every deletion path through Philter for as long as the hold is active. The hold is named (so it can be referenced in legal correspondence), scoped (so it only protects what it should), audited (so the hold lifecycle is part of the permanent record), and independently releasable (so removing one hold does not unblock evidence still covered by another).

Enforcement is within Philter. Anyone with direct access to the underlying MongoDB can still remove data, so secure and back up the database to the standard your retention obligations require.

## Hold Concepts

### Reference

Every hold has a **reference**. This is a short, human-readable identifier such as a case number, matter number, or ticket reference (for example `LIT-2026-001` or `GDPR-REQUEST-42`). The reference is unique per user and can be used to look up or release a specific hold.

The reference is arbitrary text: Philter does not interpret it. Choose a value that means something to the people who need to manage the hold.

### Scope types

Each hold has a **scope type** that determines what data it protects.

| Scope type | What it protects |
|------------|-----------------|
| `document_chain` | The ledger chain for one specific document, identified by its document ID. Only that document's chain is protected; other documents belonging to the same user can still be deleted. |
| `user` | All redaction ledger evidence for the named user. No chain belonging to that user can be deleted while this hold is active. |

Choose `document_chain` when a single document is at issue (a disputed redaction, a specific file in litigation). Choose `user` when the entire user's evidence needs to be frozen (for example, a data subject request or a regulatory inquiry covering all activity by that user).

### Multiple independent holds

Multiple holds may coexist on the same data. Releasing one hold does **not** unblock a document or user still covered by another. All active holds must be released before any deletion can proceed.

This matters in practice: if `LIT-A` and `LIT-B` both cover the same document and `LIT-A` is resolved, the document stays protected under `LIT-B` until that hold is also released. Philter enforces this automatically. There is no way to accidentally bypass a remaining hold.

## Hold Lifecycle

### 1. Set a hold

Creating a hold requires: a **reference**, a **scope type**, a **scope value** (the document ID or user email), and an optional **reason**.

From the dashboard: navigate to **Legal Holds** in the left-hand navigation, then click **Set Hold**. Fill in the form and save.

From the API:

```
POST /api/holds
Content-Type: application/json
Authorization: Bearer <api-key>

{
  "reference": "LIT-2026-001",
  "scopeType": "document_chain",
  "scopeValue": "doc-abc123",
  "reason": "Document disputed in Smith v. Acme. Preserve pending resolution."
}
```

A successful response returns **HTTP 201 Created** with the hold details. If the reference already exists for the calling user, **HTTP 409 Conflict** is returned. Choose a different reference or release the existing one first.

### 2. View active holds

Users can view all of their own holds. Administrators can view holds for all users.

From the dashboard: the **Legal Holds** page shows a table of all active holds scoped to the logged-in user. Administrators see a global view across all users.

From the API:

```
GET /api/holds
Authorization: Bearer <api-key>
```

Admins may pass `?owner=<email>` to list holds for a specific user. Pagination is supported via `?offset=` and `?limit=`.

To retrieve a specific hold by reference:

```
GET /api/holds/{reference}
Authorization: Bearer <api-key>
```

### 3. Release a hold

Once a matter is resolved, the hold must be explicitly released. Releasing a hold is audited.

From the dashboard: on the **Legal Holds** page, click the **Release** button next to the hold. Confirm the dialog to release.

From the API:

```
DELETE /api/holds/{reference}
Authorization: Bearer <api-key>
```

A successful release returns **HTTP 200 OK**. If the hold does not exist, **HTTP 404 Not Found** is returned.

## How Holds Block Deletions

The hold check runs on every deletion Philter performs. There is no way to bypass a hold through the API or the dashboard.

| Deletion operation | Hold check applied |
|--------------------|--------------------|
| `DELETE /api/ledger/{documentId}` (delete a specific document's chain) | `isProtectedDocument`: blocks if a `document_chain` hold covers that document, or if a `user` hold covers the owning user. |
| `DELETE /api/ledger?older_than_days=N` (bulk age-based purge) | `hasAnyHold`: blocks the entire purge if the user has **any** active hold. Because a bulk purge cannot selectively skip held documents, the entire operation is blocked while any hold remains. |
| Internal bulk delete during user removal | `hasAnyHold`: same as bulk purge above. |

Philter has no automatic ledger expiry, so this table is exhaustive: there is no path by which held evidence is removed without a hold check.

When a deletion is blocked:

- The operation returns **HTTP 423 Locked**.
- The response body lists the references of every hold that blocked the operation.
- The event `legal_hold_blocked_deletion` is written to the [audit log](../auditing.md), including the hold references and the user involved.

No partial deletion occurs. Either the entire requested deletion succeeds or it is blocked in full.

## Admin Access

Administrators can manage holds on behalf of any user via the `?owner=<email>` parameter on all API endpoints. They can view all holds from the **Legal Holds** dashboard page, which shows a global table rather than a per-user view. Every time an admin acts on another user's hold, an `admin_cross_user_access` audit event is recorded.

Non-admin users cannot specify an `owner` parameter that differs from themselves. Attempting to do so returns **HTTP 404 Not Found**.

## Audit Events

Every hold lifecycle action is recorded in the audit log. See [Auditing](../auditing.md) for the full audit log reference.

| Event | When it is recorded |
|-------|---------------------|
| `legal_hold_set` | A legal hold was created. Details include the hold reference, scope type, and scope value. |
| `legal_hold_released` | A legal hold was released. Details include the hold reference. |
| `legal_hold_blocked_deletion` | A deletion was blocked because one or more holds are active. Details include the references of the blocking holds. |

## API Reference

The legal holds endpoints are documented on the [Legal Holds API](../api_and_sdks/api/legal_holds_api.md) page: `POST /api/holds`, `GET /api/holds`, `GET /api/holds/{reference}`, and `DELETE /api/holds/{reference}`, including the admin `owner` parameter.

## Concurrent operations and recovery

Hold creation, hold release, individual-chain deletion, age purge, and bulk owner-evidence deletion
share one persistent guard per evidence owner in MongoDB. If deletion acquires it first, a competing
hold request returns HTTP 409 and has not established a hold. If hold creation acquires it first,
deletion cannot proceed until the hold is visible; subsequent protected deletion returns HTTP 423.
Unrelated owners proceed independently. A busy guard also returns HTTP 409 for release requests.

The guard has no timeout or automatic takeover. Expiring it could let a delayed server-side deletion
continue after a new hold has been acknowledged. Successful operations release it; an exception,
process crash, or uncertain database result leaves it held. Normal redaction and reads do not acquire
this guard. It coordinates hold/deletion ordering; it does not make multi-row deletion transactional.

If an owner's operations keep returning 409 after an interruption:

1. Inspect that owner's record in the evidence_operation_guards collection. Its _id is the owner's
   ObjectId; token, operation, and started_at identify the retained operation.
2. Stop all application instances that could resume the operation. Confirm on MongoDB that the
   corresponding command is no longer running and that its outcome is known. A process restart or
   elapsed time alone does not establish this.
3. Review the affected holds and ledger evidence, including any partial deletion, and preserve the
   recovery decision in the operator's audit records.
4. Only after establishing that no previous operation can resume, clear token, operation, and
   started_at from that owner's guard with a conditional update matching its observed token.
   Restart the instances and retry the intended operation.

Do not add a TTL index to this collection or clear guards automatically. Such a takeover would
invalidate the hold/deletion ordering guarantee.

## Evidence Types and Retention

Philter retains configuration history and redaction evidence separately. Their contents and retention controls differ.

### Retained policy versions and execution snapshots

Policy versions preserve the supplied policy JSON. That JSON can contain personal data in literal dictionary entries or ignored terms, as well as encryption keys. Raw policies and their version history are not encrypted. Use environment references for keys and review policy contents before saving them; changing a policy does not remove values from its history. See [Database](../database.md#what-is-encrypted-at-rest).

Policy version snapshots are append-only through Philter: there is no API for deleting them. Expanded execution snapshots capture the effective configuration used by a queued job, are encrypted, and remain after the job is deleted. A legal hold is not what preserves these snapshots; they have no application deletion path. Neither their configuration role nor their retention behavior establishes an exemption from your organization's data-handling obligations.

### Redaction ledger entries

Ledger entries retain encrypted original values and replacements, along with document and policy metadata and the hash-chain evidence. Treat this as sensitive information even when the redacted output no longer reveals the original values.

### Coordinating retention and deletion

Determine which records your retention or erasure process must address before using the deletion controls. Deleting a ledger chain does not delete policy history, execution snapshots, context mappings, or every other record associated with a request.

Ledger deletion requires an administrator and `LEDGER_DELETION_ENABLED=true`. A covering hold blocks deletion with HTTP 423; active or uncertain chains also have completion/recovery restrictions. Releasing a hold does not delete the evidence. Once release is authorized under your organization's process, use the [ledger deletion controls](ledgers.md) for eligible chains and handle other retained records separately. Philter's controls do not determine the legal basis for retaining or erasing a record.

## Frequently Asked Questions

**What happens if I try to purge old ledger entries while a hold is active?**

The purge is blocked entirely and returns HTTP 423. All entries are preserved until the hold is released.

**Can I delete a document chain that is NOT covered by a hold, even while a user-scope hold exists on the same user?**

No. A `user` scope hold protects **all** ledger entries for that user, including individual document chains. The per-document delete check (`isProtectedDocument`) also returns true when any `user`-scope hold is active.

**Can I release a hold by accident?**

From the dashboard, release requires explicit confirmation in a dialog. Via the API, a `DELETE` request is required; there is no bulk-release endpoint. Each release is audited so accidental releases are traceable.

**What happens to holds if a user account is deactivated?**

Holds are not affected by user deactivation. The holds remain active and continue to block any deletion attempts against that user's ledger data.

**Can two different users set holds with the same reference?**

Yes. Hold references are unique per user, not globally. `LIT-001` for user A and `LIT-001` for user B are independent holds.

**Does releasing a hold delete any ledger data?**

No. Releasing a hold only removes the hold itself. All ledger entries that were protected by the hold remain intact. You must separately use the purge or delete APIs to remove any entries.

## See Also

- [Redaction Ledgers](ledgers.md): the evidence that legal holds protect.
- [Auditing](../auditing.md): the audit log that records all hold lifecycle events.
- [User Management](../dashboard.md#user-management): users are deactivated rather than deleted, so holds survive deactivation.
