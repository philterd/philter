# Re-identification

Re-identification is the governed, audited process of reversing a cryptographic redaction to recover the original value. It is available only for values that were redacted using the `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE` strategies, which produce mathematically reversible replacements.

> **Requires authorization.** Every re-identification call requires a valid API key and a stated reason. Every reversal is recorded in the [audit log](../auditing.md#re-identification).

## When re-identification is possible

Not all redaction strategies support reversal. Re-identification works only when the original value was replaced using one of these two strategies:

| Strategy | How it works |
|---|---|
| `CRYPTO_REPLACE` | The original value is encrypted with AES-256-GCM using a key stored in the policy. The replacement is the Base64-encoded ciphertext wrapped in double braces: `{{<base64>}}`. |
| `FPE_ENCRYPT_REPLACE` | The original value is encrypted using FF3-1 format-preserving encryption. The replacement looks like the original (digits stay digits, letters stay letters). |

Strategies that produce random or static replacements (such as `REDACT`, `RANDOM_REPLACE`, or `HASH_SHA256_REPLACE`) cannot be reversed through this endpoint. If you need to recover those originals, consult the [Redaction Ledger](ledgers.md), which stores the original token alongside its replacement for every redaction in a ledger-enabled context.

## Making a re-identification request

To reverse chosen values, send a `POST` request to `/api/reidentify` with a JSON body. To restore every `CRYPTO_REPLACE` value in a text, see [Re-identifying a text](#re-identifying-a-text).


```json
{
  "values": ["<encrypted-value-1>", "<encrypted-value-2>"],
  "strategy": "CRYPTO_REPLACE",
  "policyName": "my-policy",
  "reason": "Patient care, authorized by Dr. Smith"
}
```

| Field | Required | Description |
|---|---|---|
| `values` | Yes | One or more replacement values to reverse. |
| `strategy` | Yes | `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE`. |
| `policyName` | Required for `CRYPTO_REPLACE`; optional for `FPE_ENCRYPT_REPLACE` | The policy whose cryptographic parameters were used during redaction. For `FPE_ENCRYPT_REPLACE`, omit this field to use your account's default key and derived tweak; supply it when the policy specified its own `fpe` configuration. |
| `reason` | Yes | A free-text statement of why the reversal is authorized. Recorded verbatim in the audit log. |

### Example: `CRYPTO_REPLACE`

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["k7Xv2...base64ciphertext...=="],
    "strategy": "CRYPTO_REPLACE",
    "policyName": "my-policy",
    "reason": "Patient care, authorized by Dr. Smith"
  }'
```

### Example: `FPE_ENCRYPT_REPLACE` (account default key)

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["750918814058654607"],
    "strategy": "FPE_ENCRYPT_REPLACE",
    "reason": "Fraud investigation case #4421"
  }'
```

### Example: `FPE_ENCRYPT_REPLACE` (policy key and tweak)

If the policy that produced the redaction specified its own `fpe` configuration, pass `policyName` so Philter uses its key and tweak unchanged. Account defaults apply only when the policy has no `fpe` object. An incomplete policy configuration does not fall back to account defaults.

Reversal requires the same key and tweak used for the original redaction. FPE does not authenticate ciphertext: different valid parameters can yield incorrect plaintext without a decryption error.

For example:

```bash
curl -s -X POST https://philter:8080/api/reidentify \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "values": ["750918814058654607", "018989839189395384"],
    "strategy": "FPE_ENCRYPT_REPLACE",
    "policyName": "my-policy",
    "reason": "Fraud investigation case #4421"
  }'
```

### Authorization

A user may re-identify their own values. An admin may re-identify values belonging to any user by supplying that user's username in the `owner` query parameter, which requires `ADMIN_CROSS_USER_ACCESS_ENABLED=true`:

```bash
curl -s -X POST "https://philter:8080/api/reidentify?owner=other-user" \
  -H "Authorization: Bearer ADMIN_API_KEY" \
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
      "error": "Decryption failed."
    }
  ]
}
```

A successful reversal has `decrypted` set and no `error` field. A failed reversal has `error` set (for example, if the value was tampered with or encrypted with a different key) and no `decrypted` field. The overall HTTP status is `200` even when individual values fail; inspect each result's `error` field.

## HTTP status codes

| Code | Meaning |
|---|---|
| `200 OK` | Request accepted. Check each result's `error` field for per-value failures. |
| `400 Bad Request` | Invalid request: `values` is empty, `reason` is blank, `strategy` is unrecognized, `policyName` is missing for `CRYPTO_REPLACE`, the named policy has no crypto key configured, or the policy's key names an environment variable not in [`POLICY_ALLOWED_ENV_KEYS`](../settings.md#environment-variables-in-a-policy). |
| `401 Unauthorized` | The `Authorization` header is absent or the API key is not recognized. |
| `403 Forbidden` | The API key does not have the `reidentify` scope. |
| `404 Not Found` | The specified `policyName` does not exist, or a non-admin caller supplied an `owner` that is not their own account. |
| `500 Internal Server Error` | An unexpected server-side error occurred. |

## Re-identifying a text

`POST /api/reidentify/text` takes a text, such as a redacted document or an LLM's response to a redacted prompt, and returns it with each `CRYPTO_REPLACE` value restored. Philter finds every `{{...}}` token in the text, decrypts it with the named policy's `crypto` key, and puts the original value in its place.

```bash
curl -s -X POST https://philter:8080/api/reidentify/text \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Write to {{k7Xv2...base64ciphertext...==}} about the claim.",
    "policyName": "my-policy",
    "reason": "Restore LLM response for authorized claims review"
  }'
```

| Field | Required | Description |
|---|---|---|
| `text` | Yes | The text to restore. |
| `policyName` | Yes | The policy whose `crypto` key was used during redaction. |
| `reason` | Yes | A free-text statement of why the reversal is authorized. Recorded verbatim in the audit log. |

The response carries the restored text and how many tokens were found, restored, and left unchanged:

```json
{
  "text": "Write to jane.doe@example.com about the claim.",
  "found": 1,
  "restored": 1,
  "failed": 0
}
```

Limits:

- Only `CRYPTO_REPLACE` tokens are restored. `FPE_ENCRYPT_REPLACE` replacements look like ordinary data and cannot be told apart from it in a text, so reverse them by value with `/api/reidentify`. Other strategies cannot be reversed.
- A token that does not decrypt is left in the text as it is and counted in `failed`. This includes a token encrypted under another policy's key and one whose ciphertext was altered after redaction, for example shortened by an LLM.
- A token altered so that it is no longer `{{` followed by Base64 and `}}`, for example split by a space or missing a brace, is not found, so it is counted in neither `found` nor `failed`. A model's response may therefore come back partly restored with `failed` at `0`; check the result rather than the counts alone.
- The request body is limited by `MAX_FILE_SIZE_BYTES_OTHER`.

Authorization, the `owner` parameter, and the status codes are the same as for `/api/reidentify`, except that `policyName` is always required and `text` takes the place of `values`. The response is `200` even when some tokens fail.

## Audit trail

Every call to `/api/reidentify` or `/api/reidentify/text` produces an audit event of type `redaction_reversed`, whether or not all values succeed. The event records:

- **Who**: the API key (and therefore the user) that made the call.
- **What**: the list of encrypted input values (ciphertexts), the strategy used, and how many reversals succeeded. For a text, the tokens found in it, never the text.
- **When**: the timestamp of the event.
- **Authority**: the `reason` field supplied in the request, verbatim.

If an admin used the `owner` parameter, the affected user's id is also recorded. See [Auditing → Re-identification](../auditing.md#re-identification) for the full event specification.

## Security considerations

- The `reason` field is the caller's stated authority. Philter records it but does not validate it. Enforce authorization policies at the organizational level.
- The encrypted input values (ciphertexts) are recorded in the audit log. The decrypted originals are **never** written to the audit log.
- Re-identification endpoints should be accessible only to systems and users that genuinely need reversal capability. Consider restricting access at the network or API gateway level in addition to API key authorization.

## See also

- [Redaction Policies](policies.md): how `CRYPTO_REPLACE` and `FPE_ENCRYPT_REPLACE` are configured in a policy.
- [Redaction Ledgers](ledgers.md): for recovering originals from non-cryptographic redaction strategies.
- [Auditing](../auditing.md): the full list of audit events.
