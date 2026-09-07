# Public health and signing keys

These three read-only endpoints require neither an API key nor a scope. Send `Accept: application/json`.

| Method | Endpoint | Result |
|--------|----------|--------|
| GET | `/api/health` | Liveness and version metadata: returns 200 with status `UP` when the handler can respond; does not probe dependencies. |
| GET | `/api/signing-key` | Active public key: `keyId`, `pem`, `jwk` (a JSON object), and `fingerprint`. |
| GET | `/api/signing-key/{keyId}` | Retained public key: `keyId`, `pem`, and `active`; 404 for an unknown key ID. |

```bash
curl -k -H "Accept: application/json" https://localhost:8080/api/signing-key
```

Key rotation does not invalidate historical verification: fetch the key identified by the signed evidence. These endpoints expose public keys only. See [output signing](../../output_signing.md) for signature verification and [health](filtering_api.md#health) for monitoring.
