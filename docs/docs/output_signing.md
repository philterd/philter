# Output Signing

Philter can digitally sign the redacted text it returns so consumers can cryptographically verify that a response came from a specific Philter deployment, is bound to the exact policy that governed it, and has not been tampered with in transit.

Signing is **disabled by default** and opt-in via the Admin settings page. When enabled, every successful `POST /api/filter` (text) and `POST /api/explain` response carries a compact ES256 JWT in the `X-Philter-Signature` response header.

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

On first start, if no signing key exists in the database and `PHILTER_SIGNING_KEY_PATH` is not set, Philter generates a new ES256 keypair and persists it in the `signing_keys` MongoDB collection. The key is reused across restarts and shared across all instances in a cluster (they all read from the same MongoDB collection).

### User-supplied key (optional)

Set `PHILTER_SIGNING_KEY_PATH` to the absolute path of a PKCS8 PEM file containing your private key to have Philter use it instead of the auto-generated one. The file should be in `BEGIN PRIVATE KEY` (PKCS8) format:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out my_signing_key.pem
```

When this environment variable is set, the key is loaded from the file on startup and the auto-generated MongoDB key (if any) is ignored. The file must remain accessible on every node; it is not imported into MongoDB.

### Regenerating the key

From the **Admin** → **Admin Settings** page, click **Regenerate Signing Key**. A confirmation dialog warns you that any consumer that cached the old public key will need to re-fetch it. After confirmation, a new keypair is generated and all subsequent responses are signed with it.

Regeneration is audited as `signing_key_regenerated`.

> **Warning:** regenerating the key invalidates all previously issued signatures. If consumers have saved `X-Philter-Signature` headers to verify later, they must obtain and store the corresponding public key *before* you regenerate.

## The `X-Philter-Signature` Response Header

When signing is enabled and the request succeeds (HTTP 200), Philter adds the `X-Philter-Signature` header containing a compact JWT:

```
X-Philter-Signature: eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJib2R5SGFzaCI6Ii4uLiIsInBvbGljeU5hbWUiOiJkZWZhdWx0IiwicG9saWN5VmVyc2lvbiI6MSxyZXNwb25zZUlkIjoiLi4uIiwiaWF0IjoxNzE3MDAwMDAwfQ.MEQCIB...
```

### JWT structure

**Header:**

```json
{"alg":"ES256","typ":"JWT"}
```

**Payload:**

| Field | Type | Description |
|-------|------|-------------|
| `bodyHash` | string | SHA-256 of the response body (lowercase hex). |
| `policyName` | string | Name of the policy applied during redaction. |
| `policyVersion` | integer | Revision of the policy applied during redaction. |
| `documentId` | string | The document ID returned in the `X-Document-Id` response header. |
| `iat` | integer | Unix epoch (seconds) when the JWT was issued. |

**Signature:** ES256 (ECDSA P-256 / SHA-256), using the operator's private key.

### Which responses are signed?

| Endpoint | Signed? | Notes |
|----------|---------|-------|
| `POST /api/filter` (text/plain) | Yes (200 only) | Signed when enabled. |
| `POST /api/explain` | Yes (200 only) | Signed when enabled. |
| `POST /api/filter` (PDF) | No | PDF paths are async; signing is planned for a future release (see [#256](https://github.com/philterd/philterd-website/issues/256)). |
| Error responses (4xx, 5xx) | Never | Error bodies are never signed. |

### Signing failure

If signing is enabled and the signing operation fails, Philter returns **HTTP 500** and does not return an unsigned 200 response. There is no silent fallback: a consumer that expects signatures can rely on an unsigned 200 never appearing.

## Verifying a Signature

Verification is the consumer's responsibility. Philter does not expose a server-side verification endpoint.

### Steps to verify

1. Fetch the public key from `GET /api/signing-key` (see [API Reference](#get-apisiging-key) below).
2. Decode the JWT from the `X-Philter-Signature` header (split on `.`, base64url-decode each part).
3. Verify the JWT signature using the public key and ES256.
4. Check that the `bodyHash` in the payload matches `SHA-256(response_body)`.
5. Optionally check `iat` against a clock-skew tolerance and `policyName`/`policyVersion` against your expectations.

### Example (Python)

```python
import hashlib, jwt, requests

# Fetch the public key (once; cache it)
jwk = requests.get("https://philter.example.com/api/signing-key").json()["jwk"]
from jwt.algorithms import ECAlgorithm
pubkey = ECAlgorithm.from_jwk(jwk)

# Verify a response
response = requests.post(
    "https://philter.example.com/api/filter",
    headers={"Authorization": "Bearer sk_..."},
    data="My name is John Smith.",
    params={"p": "default"},
)
token = response.headers["X-Philter-Signature"]
claims = jwt.decode(token, pubkey, algorithms=["ES256"])

body_hash = hashlib.sha256(response.content).hexdigest()
assert claims["bodyHash"] == body_hash, "body hash mismatch — response was tampered"
```

## Enabling Output Signing

1. Navigate to **Admin** → **Admin Settings** in the Philter dashboard.
2. Check **Enable output signing (ES256 JWT on X-Philter-Signature response header)**.
3. Click **Save**.

Signing is applied immediately on the next request.

## Getting the Public Key

```
GET /api/signing-key
```

No authentication is required. The response is JSON:

```json
{
  "pem": "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQY...\n-----END PUBLIC KEY-----\n",
  "jwk": {
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
- Output signing attests that the response came from a deployment holding the private key and was not modified in transit. It does not prove that the policy correctly classified all PII — that is the role of the [Redaction Ledger](redaction/ledgers.md).

## See Also

- [Settings](settings.md) — `PHILTER_SIGNING_KEY_PATH` environment variable.
- [Auditing](auditing.md) — audit events for signing key lifecycle.
- [Filtering API](api_and_sdks/api/filtering_api.md) — `X-Philter-Signature` and `X-Philter-Policy-*` response headers.
