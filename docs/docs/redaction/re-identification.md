# Re-identification

Re-identification is the governed, audited process of reversing a cryptographic redaction to recover the original value. It is available only for values that were redacted using the `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE` strategies, which produce mathematically reversible replacements.

> **Requires authorization.** Every re-identification call requires a valid API key and a stated reason. Every reversal is recorded in the [audit log](../auditing.md#re-identification).

## When re-identification is possible

Not all redaction strategies support reversal. Re-identification works only when the original value was replaced using one of these two strategies:

| Strategy | How it works |
|---|---|
| `CRYPTO_REPLACE` | The original value is encrypted with AES-256-GCM using a key stored in the policy. The replacement is a Base64-encoded ciphertext. |
| `FPE_ENCRYPT_REPLACE` | The original value is encrypted using FF3-1 format-preserving encryption. The replacement looks like the original (digits stay digits, letters stay letters). |

Strategies that produce random or static replacements — such as `REDACT`, `RANDOM_REPLACE`, or `HASH_REPLACE` — cannot be reversed through this endpoint. If you need to recover those originals, consult the [Redaction Ledger](ledgers.md), which stores the original token alongside its replacement for every redaction in a ledger-enabled context.

## Making a re-identification request

Send a `POST` request to `/api/reidentify` with a JSON body:

```json
{
  "values": ["<encrypted-value-1>", "<encrypted-value-2>"],
  "strategy": "CRYPTO_REPLACE",
  "policyName": "my-policy",
  "reason": "Patient care — authorized by Dr. Smith"
}
```

| Field | Required | Description |
|---|---|---|
| `values` | Yes | One or more replacement values to reverse. |
| `strategy` | Yes | `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE`. |
| `policyName` | Required for `CRYPTO_REPLACE`; optional for `FPE_ENCRYPT_REPLACE` | The policy whose key was used during redaction. For `FPE_ENCRYPT_REPLACE`, omit this field to use your account's default FPE key; supply it only if the policy specified a custom FPE key. |
| `reason` | Yes | A free-text statement of why the reversal is authorized. Recorded verbatim in the audit log. |

### Example: `CRYPTO_REPLACE`

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["k7Xv2...base64ciphertext...=="],
    "strategy": "CRYPTO_REPLACE",
    "policyName": "my-policy",
    "reason": "Patient care — authorized by Dr. Smith"
  }'
```

### Example: `FPE_ENCRYPT_REPLACE` (account default key)

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["750918814058654607"],
    "strategy": "FPE_ENCRYPT_REPLACE",
    "reason": "Fraud investigation case #4421"
  }'
```

### Example: `FPE_ENCRYPT_REPLACE` (policy key override)

If the policy that produced the redaction specified its own FPE key, pass `policyName` so Philter loads the matching key:

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["750918814058654607", "018989839189395384"],
    "strategy": "FPE_ENCRYPT_REPLACE",
    "policyName": "my-policy",
    "reason": "Fraud investigation case #4421"
  }'
```

### Authorization

A user may re-identify their own values. An admin may re-identify values belonging to any user by supplying that user's email in the `owner` query parameter:

```bash
curl -s -X POST "https://philter:8080/api/reidentify?owner=other@example.com" \
  -H "Authorization: ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["k7Xv2...base64ciphertext...=="],
    "strategy": "CRYPTO_REPLACE",
    "policyName": "my-policy",
    "reason": "Compliance audit"
  }'
```

Unauthenticated callers receive `401 Unauthorized`. A non-admin who names another user as owner receives `404 Not Found` (same behavior as other endpoints that support the `owner` parameter).

## Response

The response contains one result per input value, in the same order:

```json
{
  "results": [
    {
      "encrypted": "<encrypted-value-1>",
      "decrypted": "John Smith"
    },
    {
      "encrypted": "<encrypted-value-2>",
      "decrypted": null,
      "error": "Decryption failed."
    }
  ]
}
```

A successful reversal has `decrypted` set and no `error` field. A failed reversal has `error` set (for example, if the value was tampered with or encrypted with a different key) and `decrypted` is null. The overall HTTP status is `200` even when individual values fail; inspect each result's `error` field.

## HTTP status codes

| Code | Meaning |
|---|---|
| `200 OK` | Request accepted. Check each result's `error` field for per-value failures. |
| `400 Bad Request` | Invalid request: `values` is empty, `reason` is blank, `strategy` is unrecognized, `policyName` is missing for `CRYPTO_REPLACE`, or the named policy has no crypto key configured. |
| `401 Unauthorized` | The `Authorization` header is absent or the API key is not recognized. |
| `404 Not Found` | The specified `policyName` does not exist, or a non-admin caller supplied an `owner` that is not their own account. |
| `500 Internal Server Error` | An unexpected server-side error occurred. |

## Audit trail

Every call to `/api/reidentify` — whether or not all values succeed — produces an audit event of type `redaction_reversed`. The event records:

- **Who**: the API key (and therefore the user) that made the call.
- **What**: the list of encrypted input values (ciphertexts), the strategy used, and how many reversals succeeded.
- **When**: the timestamp of the event.
- **Authority**: the `reason` field supplied in the request, verbatim.

If an admin used the `owner` parameter, the affected user's id is also recorded. See [Auditing → Re-identification](../auditing.md#re-identification) for the full event specification.

## Security considerations

- The `reason` field is the caller's stated authority. Philter records it but does not validate it. Enforce authorization policies at the organizational level.
- The encrypted input values (ciphertexts) are recorded in the audit log. The decrypted originals are **never** written to the audit log.
- Re-identification endpoints should be accessible only to systems and users that genuinely need reversal capability. Consider restricting access at the network or API gateway level in addition to API key authorization.

## See also

- [Redaction Policies](policies.md) — how `CRYPTO_REPLACE` and `FPE_ENCRYPT_REPLACE` are configured in a policy.
- [Redaction Ledgers](ledgers.md) — for recovering originals from non-cryptographic redaction strategies.
- [Auditing](../auditing.md) — the full list of audit events.
