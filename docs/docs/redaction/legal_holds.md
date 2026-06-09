# Legal Holds

A **legal hold** is a named, audited instruction to Philter to never delete the redaction evidence for a specific scope of data. Legal holds are the primary mechanism for preserving governance evidence during litigation, regulatory investigation, or any situation where data must not be destroyed.

When a legal hold is active on a user's data, every deletion path in Philter — per-document delete, bulk purge, and age-based expiry — is blocked. A blocked operation returns an **HTTP 423 Locked** response and the attempt is written to the audit log. The hold must be explicitly released before any deletion proceeds.

## Why Legal Holds Exist

Redaction ledgers record a tamper-evident history of every redaction performed on every document. This history is normally deletable: users can purge old entries on demand, delete individual document chains, or configure automatic time-based expiry. For most purposes that is fine — but when evidence is needed for a legal or regulatory matter, accidental or routine deletion would destroy it.

Legal holds provide a hard, enforceable guarantee that no deletion can occur while the hold is active. The hold is named (so it can be referenced in legal correspondence), scoped (so it only protects what it should), audited (so the hold lifecycle is part of the permanent record), and independently releasable (so removing one hold does not unblock evidence still covered by another).

## Hold Concepts

### Reference

Every hold has a **reference** — a short, human-readable identifier such as a case number, matter number, or ticket reference (for example `LIT-2026-001` or `GDPR-REQUEST-42`). The reference is unique per user and can be used to look up or release a specific hold.

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

This matters in practice: if `LIT-A` and `LIT-B` both cover the same document and `LIT-A` is resolved, the document stays protected under `LIT-B` until that hold is also released. Philter enforces this automatically — there is no way to accidentally bypass a remaining hold.

## Hold Lifecycle

### 1. Set a hold

Creating a hold requires: a **reference**, a **scope type**, a **scope value** (the document ID or user email), and an optional **reason**.

From the dashboard: navigate to **Holds** in the left-hand navigation, then click **Set Hold**. Fill in the form and save.

From the API:

```
POST /api/holds
Content-Type: application/json
Authorization: Bearer <api-key>

{
  "reference": "LIT-2026-001",
  "scopeType": "document_chain",
  "scopeValue": "doc-abc123",
  "reason": "Document disputed in Smith v. Acme — preserve pending resolution"
}
```

A successful response returns **HTTP 201 Created** with the hold details. If the reference already exists for the calling user, **HTTP 409 Conflict** is returned — choose a different reference or release the existing one first.

### 2. View active holds

Users can view all of their own holds. Administrators can view holds for all users.

From the dashboard: the **Holds** page shows a table of all active holds scoped to the logged-in user. Administrators see a global view across all users.

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

From the dashboard: on the **Holds** page, click the **Release** button next to the hold. Confirm the dialog to release.

From the API:

```
DELETE /api/holds/{reference}
Authorization: Bearer <api-key>
```

A successful release returns **HTTP 200 OK**. If the hold does not exist, **HTTP 404 Not Found** is returned.

## How Holds Block Deletions

The hold check runs on every deletion path. There is no way to bypass a hold through the API or the dashboard.

| Deletion operation | Hold check applied |
|--------------------|--------------------|
| `DELETE /api/ledger/{documentId}` — delete a specific document's chain | `isProtectedDocument`: blocks if a `document_chain` hold covers that document, or if a `user` hold covers the owning user. |
| `DELETE /api/ledger?older_than_days=N` — bulk age-based purge | `hasAnyHold`: blocks the entire purge if the user has **any** active hold. Because a bulk purge cannot selectively skip held documents, the entire operation is blocked while any hold remains. |
| Internal bulk delete during user removal | `hasAnyHold`: same as bulk purge above. |

When a deletion is blocked:

- The operation returns **HTTP 423 Locked**.
- The response body lists the references of every hold that blocked the operation.
- The event `legal_hold_blocked_deletion` is written to the [audit log](../auditing.md), including the hold references and the user involved.

No partial deletion occurs. Either the entire requested deletion succeeds or it is blocked in full.

## Admin Access

Administrators can manage holds on behalf of any user via the `?owner=<email>` parameter on all API endpoints. They can view all holds from the **Holds** dashboard page, which shows a global table rather than a per-user view. Every time an admin acts on another user's hold, an `admin_cross_user_access` audit event is recorded.

Non-admin users cannot specify an `owner` parameter that differs from themselves. Attempting to do so returns **HTTP 404 Not Found**.

## Audit Events

Every hold lifecycle action is recorded in the audit log. See [Auditing](../auditing.md) for the full audit log reference.

| Event | When it is recorded |
|-------|---------------------|
| `legal_hold_set` | A legal hold was created. Details include the hold reference, scope type, and scope value. |
| `legal_hold_released` | A legal hold was released. Details include the hold reference. |
| `legal_hold_blocked_deletion` | A deletion was blocked because one or more holds are active. Details include the references of the blocking holds. |

## API Reference

The following API endpoints manage legal holds. All endpoints require authentication via a Bearer token (API key). See [API Keys](../account/api_keys.md).

### Set a hold

```
POST /api/holds
```

**Request body (JSON):**

| Field | Required | Description |
|-------|----------|-------------|
| `reference` | Yes | Unique hold identifier for the calling user (e.g. `LIT-2026-001`). |
| `scopeType` | Yes | `document_chain` or `user`. |
| `scopeValue` | Yes | The document ID (for `document_chain`) or the target user's ID (for `user`). |
| `reason` | No | Free-text description of why the hold was set. |

**Responses:**

| Status | Meaning |
|--------|---------|
| 201 Created | Hold was set. Response body contains the hold details. |
| 400 Bad Request | A required field is missing or `scopeType` is not a recognized value. |
| 401 Unauthorized | Missing or invalid API key. |
| 409 Conflict | A hold with this reference already exists for the calling user. |

### List holds

```
GET /api/holds[?owner=<email>][&offset=<n>][&limit=<n>]
```

Returns a JSON array of hold objects for the calling user (or the named owner if the caller is an admin). Default pagination: `offset=0`, `limit=25`.

**Responses:** 200 OK (array), 401 Unauthorized, 404 Not Found (non-admin `owner` specified).

### Get a specific hold

```
GET /api/holds/{reference}[?owner=<email>]
```

Returns the hold with the given reference.

**Responses:** 200 OK, 401 Unauthorized, 404 Not Found.

### Release a hold

```
DELETE /api/holds/{reference}[?owner=<email>]
```

Releases (deletes) the hold with the given reference.

**Responses:** 200 OK, 401 Unauthorized, 404 Not Found.

## Evidence Types and Retention

Philter produces two distinct categories of governance evidence, and they have different retention obligations.

### Retained policy versions

Retained policy versions are snapshots of redaction *rules* — which filter types to apply, what replacement strategies to use, and so on. They contain no personal data: no original tokens, no document content, no user identifiers beyond the policy owner. Because they are pure configuration records, no data-minimization or erasure obligation applies to them under GDPR or similar frameworks.

Policy version snapshots are **append-only and cannot be deleted**. The `PolicyVersionDataService` intentionally exposes no delete method. This means a legal hold has no effect on policy versions (there is nothing to block), and a GDPR erasure request does not apply to them.

### Redaction ledger entries

Ledger entries are different. Each entry records the encrypted replacement token alongside the original value in a hash, the document processed, the policy applied, and the timestamp. Depending on your redaction strategy, the entry may hold enough context to allow re-identification of the original value. Ledger entries therefore *may* constitute personal data under GDPR and similar frameworks, and a data subject's right to erasure (GDPR Art. 17) could apply to them.

### Reconciling erasure with evidence retention

These two obligations can appear to conflict: you may need to preserve ledger evidence for a legal matter (hold), but also be required to erase it under a data subject request.

**The general rule:** a legal obligation to preserve evidence takes precedence over a data subject's right to erasure. GDPR Art. 17(3)(b) explicitly carves out retention "for the establishment, exercise or defence of legal claims." When a legal hold is active, the evidence must be preserved regardless of an erasure request, and that preserved status should be communicated to the data subject together with the legal basis.

**When no hold is active:** ledger entries are removable via the standard purge or delete paths. Fulfilling a GDPR erasure request is straightforward: delete the relevant document chains or purge the user's entire ledger.

**When a hold is active:** a deletion or purge attempt returns HTTP 423, which is the signal that evidence is preserved under hold. Your process should:
1. Inform the data subject that erasure is temporarily deferred under a legal hold.
2. Record the deferral and its legal basis in your own records.
3. Release the hold and delete the entries once the legal matter is resolved.

**Policy versions and erasure:** policy versions contain no personal data and are therefore outside the scope of any erasure request. They do not need to be deleted to satisfy a right-to-erasure obligation.

**Practical guidance:**
- Set holds with a specific reference (case number, DSAR reference) so the legal basis is clear in the audit log.
- Use `document_chain` scope when only a specific document is at issue; use `user` scope only when the entire user's evidence needs to be preserved.
- Release holds promptly once the matter is resolved, and then fulfill any outstanding erasure requests.

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

- [Redaction Ledgers](ledgers.md) — the evidence that legal holds protect.
- [Auditing](../auditing.md) — the audit log that records all hold lifecycle events.
- [User Management](../dashboard.md#user-management) — users are deactivated rather than deleted; holds survive deactivation.
