# Output Signing

Philter can digitally sign the redacted text it returns so consumers can cryptographically verify that a response came from a specific Philter deployment, is bound to the exact policy that governed it, and has not been tampered with in transit.

Signing is **disabled by default** and opt-in via the Admin settings page. When enabled, every successful `POST /api/filter` (text) and `POST /api/explain` response carries a compact ES256 JWT in the `X-Philter-Signature` response header. PDF (binary) `POST /api/filter` responses are **not yet signed**; see [Which responses are signed?](#which-responses-are-signed) below.

## How It Works

When output signing is enabled, Philter:

1. Computes a SHA-256 hash of the response body.
2. Builds a JWT payload that binds the body hash, the applied policy name and version, the document ID, and an issue timestamp.
3. Signs the JWT header and payload with the operator's ES256 (ECDSA P-256) private key.
4. Returns the compact JWT in the `X-Philter-Signature` header.

Consumers that want to verify a response fetch the operator's public key from `GET /api/signing-key` and use it to verify the JWT signature. Verification confirms that:

- The response body was not modified after Philter signed it.
- The response came from the deployment that holds the private key.
- The exact policy (name and version) stated in the JWT governed the redaction.

## The Signing Key

Philter uses an ES256 (ECDSA P-256 / SHA-256) keypair. The key belongs to the **operator** running Philter, not to Philterd.

### Auto-generated key

On first start, if no active signing-key pointer exists in the database and `PHILTER_SIGNING_KEY_PATH` is not set, Philter generates a new ES256 keypair and persists it in the `signing_keys` MongoDB collection. A single document in the signing_key_state collection selects the active key. Each signing operation reads that pointer from the MongoDB primary; decrypted key material is reused only when its ID still matches. Instances therefore observe rotation on their next key selection, without a restart or cache TTL.

The **private** key is encrypted at rest under `PHILTER_ENCRYPTION_KEY`, so a database dump on its own does not yield the ability to forge signatures: an attacker would need both the database and the deployment's master key. The **public** key is stored in the clear, because it is not a secret and `GET /api/signing-key` serves it without authentication so signatures can be verified.

New key material is stored with majority write concern before the active pointer is atomically published with majority write concern. Concurrent startup selects one active pointer; unused startup candidates may remain in key history. Concurrent rotations are ordered by pointer publication. If the pointer cannot be read or its key cannot be loaded, signing fails rather than using a cached superseded key. No plaintext-key migration or fallback is supported.

### User-supplied key (optional)

Set `PHILTER_SIGNING_KEY_PATH` to the absolute path of a PKCS8 PEM file containing your private key to have Philter use it instead of the auto-generated one. The file should be in `BEGIN PRIVATE KEY` (PKCS8) format:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out my_signing_key.pem
```

When this environment variable is set, the key is loaded from the file on startup and the auto-generated MongoDB key (if any) is ignored. Every node must use the same file contents and key-management mode. The private key is not imported into MongoDB; the public key is retained there by ID for historical verification.

The private signing key never enters the database, so database access alone does not yield it. Restrict who can read the file. Use it where the signature has to withstand compromise of the database itself, for example when the [redaction ledger](redaction/ledgers.md) is relied on as evidence.

To rotate a file-managed key, replace the PEM on every node and restart all instances. Dashboard regeneration is disabled in this mode, and the service rejects regeneration attempts. During a rolling replacement, nodes may sign with different keys; the signature key ID selects the matching retained public key.

### Regenerating the key

From the **Admin** → **Admin Settings** page, click **Regenerate Signing Key**. A confirmation dialog warns you that any consumer that cached the old public key will need to re-fetch it. For database-managed keys, confirmation stores a new keypair and publishes its ID for all instances. Operations that already selected the previous key may finish with it; subsequent key selections use the published key.

Regeneration is audited as `signing_key_regenerated`.

Regeneration preserves previous public keys and does not invalidate historical signatures. Each JWT carries a `kid` header; fetch its key from `GET /api/signing-key/{keyId}`. Cache verification keys by ID.

## The `X-Philter-Signature` Response Header

When signing is enabled and the request succeeds (HTTP 200), Philter adds the `X-Philter-Signature` header containing a compact JWT:

```
X-Philter-Signature: eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJib2R5SGFzaCI6Ii4uLiIsInBvbGljeU5hbWUiOiJkZWZhdWx0IiwicG9saWN5VmVyc2lvbiI6MSxyZXNwb25zZUlkIjoiLi4uIiwiaWF0IjoxNzE3MDAwMDAwfQ.MEQCIB...
```

### JWT structure

**Header:**

```json
{"alg":"ES256","typ":"JWT","kid":"0123456789abcdef"}
```

**Payload:**

| Field | Type | Description |
|-------|------|-------------|
| `bodyHash` | string | SHA-256 of the response body (lowercase hex). |
| `policyName` | string | Name of the policy applied during redaction. |
| `policyVersion` | integer | Revision of the policy applied during redaction. |
| `documentId` | string | The document ID returned in the `X-Document-Id` response header, which is also the ID of the redaction ledger chain when the context has the ledger enabled. |
| `iat` | integer | Unix epoch (seconds) when the JWT was issued. |

**Signature:** ES256 (ECDSA P-256 / SHA-256), using the operator's private key.

### Which responses are signed?

| Endpoint | Signed? | Notes |
|----------|---------|-------|
| `POST /api/filter` (text/plain) | Yes (200 only) | Signed when enabled, or when the request passes `sign=true`. |
| `POST /api/explain` | Yes (200 only) | Signed when enabled, or when the request passes `sign=true`. |
| `POST /api/filter` (PDF) | No | PDF paths are async; signing is planned for a future release (see [#72](https://github.com/philterd/philter/issues/72)). |
| Error responses (4xx, 5xx) | Never | Error bodies are never signed. |

### Requesting a signature per request

A caller can ask for a signature on an individual request by passing `sign=true` to
`POST /api/filter` (text) or `POST /api/explain`, without an operator enabling signing for the whole
deployment. This is useful when one integration needs attested output and the rest does not.

| Admin setting | Request asks | Result |
|---|---|---|
| On | anything | Signed |
| Off | no | Unsigned |
| Off | yes | Signed |

**The admin setting is a floor, and a request can only add signing, never remove it.** With signing
enabled, every response is signed regardless of what the request asks, and `sign=false` does not
change that. An operator who has enabled signing keeps the claim that every response the deployment
returned was attested.

The direction is deliberate. The signature protects the recipient of the redacted output and the
operator who has to demonstrate compliance, not the caller, so the caller is the wrong party to be
able to switch it off. If a request could suppress signing, the guarantee would weaken from "every
response was attested under a named policy" to "attested when the caller asked", and the failure
would be silent: an unsigned response looks the same whether it was suppressed or never requested.

For the same reason this is a request parameter rather than a policy field. Policies are writable by
any user holding `policies:write`, so putting the switch there would hand it to the party being
attested.

A signature produced by `sign=true` is identical in form to one produced by the admin setting and
verifies the same way against `GET /api/signing-key`.

### Signing failure

If signing is enabled and the signing operation fails, Philter returns **HTTP 500** and does not return an unsigned 200 response. There is no silent fallback: a consumer that expects signatures can rely on an unsigned 200 never appearing.

## Verifying a Signature

Verification is the consumer's responsibility. Philter does not expose a server-side verification endpoint.

### Steps to verify

1. Read the JWT header and its `kid` from `X-Philter-Signature`. Treat it only as a key selector until verification succeeds.
2. Fetch the matching public key from your trusted Philter server at `GET /api/signing-key/{keyId}` (or use a key cached by that ID).
3. Verify the JWT signature using the public key and ES256.
4. Check that the `bodyHash` in the payload matches `SHA-256(response_body)`.
5. Optionally check `iat` against a clock-skew tolerance and `policyName`/`policyVersion` against your expectations.

### Example (Python)

```python
import hashlib, jwt, requests

# Verify a response
response = requests.post(
    "https://philter.example.com/api/filter",
    headers={"Authorization": "Bearer sk_..."},
    data="My name is John Smith.",
    params={"p": "default"},
)
token = response.headers["X-Philter-Signature"]
key_id = jwt.get_unverified_header(token)["kid"]
# IDs are hex strings; reject unexpected input before using it in a URL.
assert len(key_id) == 16 and all(c in "0123456789abcdef" for c in key_id)
key_response = requests.get(f"https://philter.example.com/api/signing-key/{key_id}")
key_response.raise_for_status()
claims = jwt.decode(token, key_response.json()["pem"], algorithms=["ES256"])

body_hash = hashlib.sha256(response.content).hexdigest()
assert claims["bodyHash"] == body_hash, "body hash mismatch, response was tampered"
```

## Enabling Output Signing

1. Navigate to **Admin** → **Admin Settings** in the Philter dashboard.
2. Check **Enable output signing (ES256 JWT on X-Philter-Signature response header)**.
3. Click **Save**.

Signing is applied immediately on the next request, and applies to every response from then on: once
enabled, it cannot be turned off by a caller. To sign only some requests instead, leave the setting
off and pass `sign=true` on the requests that need it (see
[Requesting a signature per request](#requesting-a-signature-per-request)).

## Getting the Public Key

```
GET /api/signing-key
```

No authentication is required. The response is JSON:

```json
{
  "keyId": "0123456789abcdef",
  "pem": "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQY...\n-----END PUBLIC KEY-----\n",
  "jwk": {
    "kid": "0123456789abcdef",
    "kty": "EC",
    "crv": "P-256",
    "x": "...",
    "y": "..."
  },
  "fingerprint": "aa:bb:cc:dd:..."
}
```

| Field | Description |
|-------|-------------|
| `keyId` | Stable ID of the advertised key; matches JWK `kid`. |
| `pem` | X.509 SubjectPublicKeyInfo in PEM format (BEGIN PUBLIC KEY). |
| `jwk` | EC JWK with `kty=EC`, `crv=P-256`, and the uncompressed public point coordinates. |
| `fingerprint` | SHA-256 fingerprint of the DER-encoded public key, colon-separated hex. Use this to verify the key has not changed after a regeneration. |

## Environment Variable Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `PHILTER_SIGNING_KEY_PATH` | Absolute path to a PKCS8 PEM private key file. When set, Philter uses this key instead of the auto-generated one. The file must be accessible on every node. | (none; auto-generate) |

See also [Settings](settings.md) for the full environment variable reference.

## Audit Events

| Event | When recorded |
|-------|---------------|
| `signing_key_generated` | A new keypair was auto-generated on first start (no existing key found). |
| `signing_key_regenerated` | The signing key was regenerated via the Admin UI. |

See [Auditing](auditing.md) for the full audit log reference.

## Security Considerations

- The private key is stored in the `signing_keys` MongoDB collection. Restrict database access accordingly.
- Consumers must trust the channel through which they receive the public key. Serve `GET /api/signing-key` over HTTPS.
- The key fingerprint on the Admin Settings page allows quick visual confirmation that the public key has not changed unexpectedly.
- Output signing attests that the response came from a deployment holding the private key and was not modified in transit. It does not prove that the policy correctly classified all PII. That is the role of the [Redaction Ledger](redaction/ledgers.md).

## See Also

- [Settings](settings.md): the `PHILTER_SIGNING_KEY_PATH` environment variable.
- [Auditing](auditing.md): audit events for signing key lifecycle.
- [Redaction API](api_and_sdks/api/filtering_api.md): the `X-Philter-Signature` and `X-Philter-Policy-*` response headers.
